package dev.deskflow;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.sql.Timestamp;
import java.util.*;

@Service
class Store {
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    ProjectManager projects;
    final JdbcTemplate db; final TransactionTemplate tx; final Secrets secrets;
    Store(JdbcTemplate db,TransactionTemplate tx,Secrets secrets) { this.db=db;this.tx=tx;this.secrets=secrets; }
    static Map<String,Object> clean(Map<String,Object> row) {
        var result=new LinkedHashMap<String,Object>();
        row.forEach((k,v)->{
            if(v instanceof Timestamp t) v=t.toInstant().toString();
            else if(v!=null && v.getClass().getName().equals("org.postgresql.util.PGobject")) v=Json.read(v.toString());
            result.put(k,v);
        });
        return result;
    }
    List<Map<String,Object>> rows(String sql,Object... args) { return db.queryForList(sql,args).stream().map(Store::clean).toList(); }
    Map<String,Object> workflow(UUID owner,UUID id) { return clean(db.queryForMap("select * from workflows where owner_id=? and id=?",owner,id)); }
    void credentialRefs(UUID owner,Graph graph) {
        for(var node:graph.nodes) {
            if(Set.of("subworkflow","loop").contains(node.kind())){
                var child=workflow(owner,UUID.fromString(node.config().path("workflowId").asText()));
                if(Boolean.TRUE.equals(child.get("archived")))throw new IllegalArgumentException("Child workflow is archived");
            }
            if(node.kind().equals("table")&&db.queryForObject("select count(*) from data_tables where id=? and owner_id=?",Integer.class,UUID.fromString(node.config().path("tableId").asText()),owner)!=1)throw new IllegalArgumentException("Table does not belong to this workspace");
            String ref=node.config().path("credentialId").asText("");
            if(!ref.isBlank()) {
                UUID id; try{id=UUID.fromString(ref);}catch(Exception e){throw new IllegalArgumentException("Invalid credential reference");}
                if(db.queryForObject("select count(*) from credentials where id=? and owner_id=?",Integer.class,id,owner)!=1) throw new IllegalArgumentException("Credential does not belong to this workspace");
            }
        }
    }
    Map<String,Object> save(UUID owner,UUID id,JsonNode body) {
        String name=Json.required(body,"name",100); String description=Json.text(body,"description","");
        if(description.length()>500) throw new IllegalArgumentException("Description is too long");
        var definition=body.path("definition"); var graph=new Graph(definition); credentialRefs(owner,graph);
        boolean active=body.path("active").asBoolean();
        return tx.execute(status->{
            int version=1;
            UUID workflowId=id==null?UUID.randomUUID():id;
            if(id==null) {
                db.update("insert into workflows(id,owner_id,name,description,active,definition,webhook_token) values(?,?,?,?,?,?::jsonb,?)",workflowId,owner,name,description,active,Json.write(definition),Secrets.token());
            } else {
                var old=db.queryForMap("select version,archived from workflows where id=? and owner_id=? for update",id,owner);
                if(active&&Boolean.TRUE.equals(old.get("archived")))throw new IllegalArgumentException("Unarchive this workflow before activating it");
                int current=((Number)old.get("version")).intValue();
                if(body.path("version").asInt()!=current) throw new ResponseStatusException(HttpStatus.CONFLICT,"Workflow changed in another tab. Reopen it before saving.");
                version=current+1;
                db.update("update workflows set name=?,description=?,active=?,definition=?::jsonb,version=?,updated_at=now() where id=?",name,description,active,Json.write(definition),version,id);
            }
            db.update("insert into workflow_versions(workflow_id,version,definition) values(?,?,?::jsonb)",workflowId,version,Json.write(definition));
            java.sql.Timestamp next=graph.trigger().kind().equals("schedule")&&active?java.sql.Timestamp.from(Schedules.next(graph.trigger().config(),java.time.Instant.now())):null;
            db.update("update workflows set next_run_at=? where id=?",next,workflowId);
            return workflow(owner,workflowId);
        });
    }
    UUID enqueue(Map<String,Object> wf,JsonNode input,String type,String dedupe) {
        Json.bounded(input);
        if(Boolean.TRUE.equals(wf.get("archived")))throw new IllegalArgumentException("Unarchive this workflow before running it");
        if(dedupe!=null && (dedupe.isBlank() || dedupe.length()>200)) throw new IllegalArgumentException("Idempotency-Key must be 1–200 characters");
        return tx.execute(s->{
            UUID id=UUID.randomUUID();
            int count=db.update("insert into executions(id,workflow_id,owner_id,workflow_name,version,definition,input,trigger_type,dedupe_key) values(?,?,?,?,?,?::jsonb,?::jsonb,?,?) on conflict(workflow_id,dedupe_key) do nothing",id,wf.get("id"),wf.get("owner_id"),wf.get("name"),wf.get("version"),Json.write(wf.get("definition")),Json.write(input),type,dedupe);
            if(count==0) {
                var existing=db.queryForMap("select id,input,trigger_type from executions where workflow_id=? and dedupe_key=?",wf.get("id"),dedupe);
                if(!Json.read(existing.get("input").toString()).equals(input)||!type.equals(existing.get("trigger_type"))) throw new ResponseStatusException(HttpStatus.CONFLICT,"Idempotency-Key already used with a different payload or trigger");
                return (UUID)existing.get("id");
            }
            var graph=new Graph((JsonNode)wf.get("definition"));
            Object selected = wf.get("_instance");
            if (selected == null && graph.settings.hasNonNull("instanceId")) {
                selected = UUID.fromString(graph.settings.path("instanceId").asText());
            }
            if (selected != null) {
                UUID instanceId = UUID.fromString(selected.toString());
                UUID owner = (UUID)wf.get("owner_id");
                var instance = instance(owner,instanceId);
                if (!Set.of("READY", "BUSY").contains(instance.get("status"))) {
                    throw new IllegalArgumentException("Instance is not ready");
                }
                db.update("update executions set project_id=?,instance_id=?,state_fingerprint=? where id=? and owner_id=?",instance.get("project_id"),instanceId,projects.fingerprint(owner,instanceId),id,owner);
            } else if (graph.nodes.stream().anyMatch(n -> n.kind().startsWith("project."))) {
                throw new IllegalArgumentException("Select an instance for project steps");
            }
            db.update("update executions set root_id=id,effect_limit=? where id=?",graph.settings.path("effectLimit").asInt(100),id);
            for(var node:graph.nodes) db.update("insert into steps(execution_id,node_id,label,type) values(?,?,?,?)",id,node.id(),node.label(),node.kind());
            db.update("insert into outbox(execution_id) values(?)",id);
            return id;
        });
    }
    Map<String,Object> execution(UUID owner,UUID id) {
        var row=clean(db.queryForMap("select id,workflow_id,workflow_name,version,input,status,trigger_type,created_at,started_at,finished_at,cancel_requested,error,parent_id,root_id,effect_count,effect_limit,project_id,instance_id,state_fingerprint from executions where id=? and owner_id=?",id,owner));
        row.put("steps",rows("select node_id,label,type,status,attempts,output,error,state,started_at,finished_at from steps where execution_id=? order by started_at nulls last,node_id",id));
        row.put("approvals",rows("select id,node_id,title,status,created_at,decided_at from approvals where execution_id=?",id));
        row.put("children",rows("select id,workflow_name,status,parent_node_id,child_index from executions where parent_id=? order by child_index",id));
        UUID root=row.get("root_id")==null?id:(UUID)row.get("root_id");
        if(!root.equals(id))row.putAll(db.queryForMap("select effect_count,effect_limit from executions where id=?",root));
        row.put("effects",rows("select execution_id,node_id,kind,target,created_at from effect_events where root_id=? order by id limit 1000",root));
        return row;
    }
    Map<String,Object> project(UUID owner, UUID id) {
        return clean(db.queryForMap("select * from projects where id=? and owner_id=?", id, owner));
    }
    Map<String,Object> instance(UUID owner, UUID id) {
        return clean(db.queryForMap("select * from project_instances where id=? and owner_id=?", id, owner));
    }
    boolean claimInstance(UUID owner, UUID instance, UUID run) {
        return tx.execute(status -> {
            db.queryForMap("select id from users where id=? for update",owner);
            UUID root = db.queryForObject("select coalesce(root_id,id) from executions where id=? and owner_id=?",UUID.class,run,owner);
            int active = db.queryForObject("select count(*) from project_instances where owner_id=? and lease_token is not null and lease_token<>?",Integer.class,owner,root);
            if (active >= projects.env.getProperty("app.projects.max-concurrent-runs",Integer.class,2)) { return false; }
            return db.update("update project_instances set lease_token=?,lease_until=now()+interval '90 seconds',status='BUSY' where id=? and owner_id=? and status in ('READY','BUSY') and (lease_token=? or lease_token is null) and not exists(select 1 from executions e where e.owner_id=? and e.instance_id=project_instances.id and e.id<>? and e.status='RUNNING' and e.lease_until>now())",root,instance,owner,root,owner,run) == 1;
        });
    }
    void releaseInstance(UUID owner, UUID instance, UUID run, boolean terminal) {
        UUID root = db.queryForObject("select coalesce(root_id,id) from executions where id=? and owner_id=?",UUID.class,run,owner);
        if (terminal && root.equals(run)) {
            db.update("update project_instances set lease_token=null,lease_until=null,status='READY' where id=? and owner_id=? and lease_token=?",instance,owner,root);
        }
    }
    String redact(UUID owner,String value) {
        String result=value;
        for(String encoded:db.queryForList("select encrypted_value from credentials where owner_id=?",String.class,owner)) {
            var secret=secrets.decrypt(encoded);
            result=result.replace(secret,"[REDACTED]");
            String jsonEscaped=Json.write(secret); jsonEscaped=jsonEscaped.substring(1,jsonEscaped.length()-1);
            result=result.replace(jsonEscaped,"[REDACTED]");
        }
        return result;
    }
}
