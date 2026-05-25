package dev.deskflow;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController
@RequestMapping("/api")
class WorkspaceController {
    final Store store;final DataService data;final ImpactLab lab;
    WorkspaceController(Store store,DataService data,ImpactLab lab){this.store=store;this.data=data;this.lab=lab;}
    UUID owner(HttpServletRequest request){return (UUID)request.getAttribute("userId");}
    @GetMapping("/workflows/{id}/versions") Object versions(@PathVariable UUID id,HttpServletRequest r){store.workflow(owner(r),id);return store.rows("select version,definition,created_at from workflow_versions where workflow_id=? order by version desc limit 100",id);}
    @PostMapping("/workflows/{id}/restore/{version}") Object restore(@PathVariable UUID id,@PathVariable int version,HttpServletRequest r){
        var wf=store.workflow(owner(r),id);var definition=Json.read(store.db.queryForObject("select definition::text from workflow_versions where workflow_id=? and version=?",String.class,id,version));
        return store.save(owner(r),id,Json.M.valueToTree(Json.map("name",wf.get("name"),"description",wf.get("description"),"active",false,"version",wf.get("version"),"definition",definition)));
    }
    @PostMapping("/workflows/{id}/duplicate") Object duplicate(@PathVariable UUID id,HttpServletRequest r){var wf=store.workflow(owner(r),id);String name=wf.get("name").toString();return store.save(owner(r),null,Json.M.valueToTree(Json.map("name",name.substring(0,Math.min(90,name.length()))+" copy","description",wf.get("description"),"active",false,"definition",wf.get("definition"))));}
    @PostMapping("/workflows/{id}/archive") Object archive(@PathVariable UUID id,@RequestBody JsonNode body,HttpServletRequest r){store.workflow(owner(r),id);store.db.update("update workflows set archived=?,active=false,next_run_at=null,updated_at=now() where id=?",body.path("archived").asBoolean(true),id);return Map.of("ok",true);}
    @PostMapping("/workflows/{id}/preview") Object preview(@PathVariable UUID id,@RequestBody JsonNode body,HttpServletRequest r){
        var wf=store.workflow(owner(r),id);JsonNode definition=(JsonNode)wf.get("definition");
        if(body.has("version"))definition=Json.read(store.db.queryForObject("select definition::text from workflow_versions where workflow_id=? and version=?",String.class,id,body.path("version").asInt()));
        return lab.simulate(owner(r),definition,body.path("input"),body.has("mocks")?body.path("mocks"):Json.M.createObjectNode());
    }
    @PostMapping("/workflows/{id}/compare") Object compare(@PathVariable UUID id,@RequestBody JsonNode body,HttpServletRequest r){
        var wf=store.workflow(owner(r),id);var before=Json.read(store.db.queryForObject("select definition::text from workflow_versions where workflow_id=? and version=?",String.class,id,body.path("baselineVersion").asInt()));
        return lab.compare(owner(r),before,(JsonNode)wf.get("definition"),body.path("input"),body.has("mocks")?body.path("mocks"):Json.M.createObjectNode());
    }
    @GetMapping("/approvals") Object approvals(HttpServletRequest r){return store.rows("select a.*,e.workflow_name from approvals a join executions e on e.id=a.execution_id where a.owner_id=? and a.status='PENDING' and e.status in ('QUEUED','RUNNING','WAITING','RETRYING') order by a.created_at desc limit 100",owner(r));}
    @PostMapping("/approvals/{id}/decision") Object decide(@PathVariable UUID id,@RequestBody JsonNode body,HttpServletRequest r){
        if(!body.path("approved").isBoolean())throw new IllegalArgumentException("approved must be true or false");
        return store.tx.execute(s->{
            var row=store.db.queryForMap("select * from approvals where id=? and owner_id=? for update",id,owner(r));
            if(!row.get("status").equals("PENDING"))throw new ResponseStatusException(HttpStatus.CONFLICT,"Approval was already decided");
            var run=store.db.queryForMap("select status from executions where id=?",row.get("execution_id"));
            if(!Set.of("WAITING","RUNNING","QUEUED").contains(run.get("status")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Execution is no longer awaiting a decision");
            store.db.update("update approvals set status=?,decision_by=?,decided_at=now() where id=?",body.path("approved").asBoolean()?"APPROVED":"REJECTED",owner(r),id);
            store.db.update("update executions set next_attempt_at=now() where id=?",row.get("execution_id"));store.db.update("insert into outbox(execution_id) values(?)",row.get("execution_id"));return Map.of("ok",true);
        });
    }
    @PostMapping("/executions/{id}/resume") Object resume(@PathVariable UUID id,HttpServletRequest r){
        store.execution(owner(r),id);
        if(!store.db.queryForObject("select status from executions where id=?",String.class,id).equals("FAILED"))throw new ResponseStatusException(HttpStatus.CONFLICT,"Only failed executions can be resumed");
        if(Boolean.TRUE.equals(store.db.queryForObject("with recursive tree as (select id,lease_until from executions where id=? union all select e.id,e.lease_until from executions e join tree t on e.parent_id=t.id) select exists(select 1 from tree where lease_until>now())",Boolean.class,id)))throw new ResponseStatusException(HttpStatus.CONFLICT,"A child action is still in flight; wait for its lease before resuming");
        store.tx.executeWithoutResult(s->resumeTree(id,owner(r),0));return Map.of("id",id);
    }
    void resumeTree(UUID id,UUID owner,int depth){
        if(depth>5)throw new IllegalArgumentException("Execution depth exceeded");
        for(var child:store.db.queryForList("select id from executions where parent_id=? and owner_id=? and status<>'SUCCEEDED'",UUID.class,id,owner))resumeTree(child,owner,depth+1);
        store.db.update("update steps set status='PENDING',retry_attempts=0,error=null,finished_at=null where execution_id=? and status in ('FAILED','SKIPPED','WAITING','RETRYING','RUNNING')",id);
        store.db.update("update approvals set status='PENDING',decision_by=null,decided_at=null where execution_id=? and status='CANCELLED'",id);
        store.db.update("update executions set status='QUEUED',cancel_requested=false,error=null,finished_at=null,next_attempt_at=now(),lease_token=null,lease_until=null where id=? and owner_id=?",id,owner);
        store.db.update("insert into outbox(execution_id) values(?)",id);
    }
    @GetMapping("/tables") Object tables(HttpServletRequest r){return store.rows("select t.*,(select count(*) from data_records d where d.table_id=t.id) as record_count from data_tables t where owner_id=? order by created_at desc",owner(r));}
    @PostMapping("/tables") Object createTable(@RequestBody JsonNode body,HttpServletRequest r){var id=UUID.randomUUID();store.db.update("insert into data_tables(id,owner_id,name) values(?,?,?)",id,owner(r),Json.required(body,"name",100));return data.table(owner(r),id);}
    @GetMapping("/tables/{id}/records") Object records(@PathVariable UUID id,HttpServletRequest r){data.table(owner(r),id);return store.rows("select record_key,value,updated_at from data_records where table_id=? order by updated_at desc limit 100",id);}
    @PutMapping("/tables/{id}/records/{key}") Object upsert(@PathVariable UUID id,@PathVariable String key,@RequestBody JsonNode body,HttpServletRequest r){return data.action(owner(r),Json.M.valueToTree(Json.map("tableId",id,"key",key,"operation","upsert","value",body)));}
    @DeleteMapping("/tables/{id}/records/{key}") Object remove(@PathVariable UUID id,@PathVariable String key,HttpServletRequest r){return data.action(owner(r),Json.M.valueToTree(Json.map("tableId",id,"key",key,"operation","delete")));}
}
