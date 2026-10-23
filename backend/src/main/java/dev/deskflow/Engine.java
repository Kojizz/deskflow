package dev.deskflow;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
class Engine {
    final Store store;final Actions actions;final DurableSteps durable;
    static final Set<String> TERMINAL=Graph.TERMINAL;
    Engine(Store store,Actions actions,DurableSteps durable){this.store=store;this.actions=actions;this.durable=durable;}
    void advance(UUID id){
        UUID lease=UUID.randomUUID();
        var claimed=store.db.queryForList("update executions set lease_token=?,lease_until=now()+interval '90 seconds',status='RUNNING',started_at=coalesce(started_at,now()) where id=? and status in ('QUEUED','RUNNING','RETRYING','WAITING') and next_attempt_at<=now() and (lease_until is null or lease_until<now()) returning *",lease,id);
        if(claimed.isEmpty())return;
        var run=Store.clean(claimed.getFirst());UUID owner=(UUID)run.get("owner_id");
        try{
            if (run.get("instance_id") instanceof UUID instanceId && store.instance(owner,instanceId).get("status").equals("STALE")) {
                finish(id,lease,"FAILED","Inspect before resume: the instance lease expired or a project action failed");
                return;
            }
            if (run.get("instance_id") instanceof UUID instance && !store.claimInstance(owner,instance,id)) {
                release(id,lease,"QUEUED",2);
                return;
            }
            boolean rootCancelled=store.db.queryForObject("select cancel_requested or status in ('FAILED','CANCELLED') from executions where id=?",Boolean.class,run.get("root_id")==null?id:run.get("root_id"));
            if((boolean)run.get("cancel_requested")||rootCancelled){finish(id,lease,"CANCELLED",null);return;}
            var graph=new Graph((JsonNode)run.get("definition"));
            var stepRows=store.rows("select * from steps where execution_id=?",id);
            var steps=new LinkedHashMap<String,Map<String,Object>>();
            var context=Json.M.createObjectNode();context.set("input",(JsonNode)run.get("input"));var outputs=context.putObject("steps");
            for(var s:stepRows){steps.put((String)s.get("node_id"),s);if(Set.of("SUCCEEDED","RECOVERED").contains(s.get("status")))outputs.set((String)s.get("node_id"),(JsonNode)s.get("output"));}
            if(stepRows.stream().allMatch(s->TERMINAL.contains(s.get("status")))){finish(id,lease,"SUCCEEDED",null);return;}
            Graph.Node ready=null;boolean skip=false;
            for(var node:graph.nodes){
                if(TERMINAL.contains(steps.get(node.id()).get("status")))continue;
                var incoming=graph.incoming(node.id());
                if(!incoming.stream().allMatch(e->TERMINAL.contains(steps.get(e.source()).get("status"))))continue;
                ready=node;
                skip=!incoming.isEmpty()&&incoming.stream().noneMatch(e->edgeActive(graph,e,steps));break;
            }
            if(ready==null){finish(id,lease,"FAILED","No executable step found");return;}
            if(skip){store.db.update("update steps set status='SKIPPED',finished_at=now() where execution_id=? and node_id=?",id,ready.id());release(id,lease,"RUNNING",0);return;}
            var node=ready;var previous=steps.get(node.id());boolean resuming=previous.get("status").equals("WAITING");
            int attempts=((Number)previous.get("retry_attempts")).intValue();int limit=node.config().path("maxAttempts").asInt(3);
            if(!resuming&&(attempts>=limit || (nodeMutates(previous) && attempts>0))){store.db.update("update steps set status='FAILED',error='Attempt limit reached after interruption',finished_at=now() where execution_id=? and node_id=?",id,node.id());finish(id,lease,"FAILED","Attempt limit reached after interruption; inspect external effects before resuming");return;}
            var parents=context.putObject("parents");
            for(var e:graph.incoming(node.id()))if(edgeActive(graph,e,steps))parents.set(e.source(),(JsonNode)steps.get(e.source()).get("output"));
            store.db.update("update steps set status='RUNNING',attempts=attempts+?,retry_attempts=retry_attempts+?,started_at=coalesce(started_at,now()),error=null where execution_id=? and node_id=?",resuming?0:1,resuming?0:1,id,node.id());
            try{
                JsonNode result;
                if(DurableSteps.supports(node.kind())){
                    var outcome=durable.poll(node,context,run,(JsonNode)previous.get("state"));
                    if(outcome.waiting()){
                        store.tx.executeWithoutResult(s->{store.db.update("update steps set status='WAITING' where execution_id=? and node_id=?",id,node.id());release(id,lease,"WAITING",outcome.waitSeconds());});return;
                    }
                    result=outcome.output();
                }else result=actions.execute(node,context,owner,id);
                String output=store.redact(owner,Json.write(result));
                if(output.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>65536)throw new Actions.Failure("Step output exceeds 64 KB",false);
                store.tx.executeWithoutResult(s->{store.db.update("update steps set status='SUCCEEDED',output=?::jsonb,error=null,finished_at=now() where execution_id=? and node_id=?",output,id,node.id());release(id,lease,"RUNNING",0);});
            }catch(Exception e){
                if (node.kind().equals("project.command") || node.kind().equals("project.git")) {
                    store.db.update("update project_instances set status='STALE' where id=? and owner_id=?",run.get("instance_id"),owner);
                }
                boolean retry=e instanceof Actions.Failure f&&f.retry&&attempts+1<limit;
                String error=e instanceof Actions.Failure||e instanceof IllegalArgumentException?e.getMessage():"Action failed; check configuration";
                error=store.redact(owner,error==null?"Action failed":error);
                if(retry){store.db.update("update steps set status='RETRYING',error=? where execution_id=? and node_id=?",error,id,node.id());release(id,lease,"RETRYING",Math.min(300,node.config().path("retryDelaySeconds").asInt(2)*(1<<Math.min(attempts,6))));}
                else if(!node.config().path("onError").asText("stop").equals("stop")){
                    store.db.update("update steps set status='RECOVERED',output=?::jsonb,error=?,finished_at=now() where execution_id=? and node_id=?",Json.write(Map.of("error",Map.of("message",error))),error,id,node.id());release(id,lease,"RUNNING",0);
                }else{store.db.update("update steps set status='FAILED',error=?,finished_at=now() where execution_id=? and node_id=?",error,id,node.id());finish(id,lease,"FAILED",error);}
            }
        }catch(Exception e){finish(id,lease,"FAILED","Execution could not continue; check workflow and credentials");}
        finally {
            if (run.get("instance_id") instanceof UUID instance) {
                String state = store.db.queryForObject("select status from executions where id=? and owner_id=?",String.class,id,owner);
                store.releaseInstance(owner,instance,id,Set.of("SUCCEEDED","FAILED","CANCELLED").contains(state));
            }
        }
    }
    static boolean nodeMutates(Map<String,Object> step) {
        return Set.of("project.command", "project.git").contains(step.get("type"));
    }
    static boolean edgeActive(Graph graph,Graph.Edge edge,Map<String,Map<String,Object>> steps){var s=steps.get(edge.source());return Graph.active(graph.find(edge.source()),(String)s.get("status"),(JsonNode)s.get("output"),edge.handle());}
    void release(UUID id,UUID lease,String status,int delay){
        store.tx.executeWithoutResult(s->{int changed=store.db.update("update executions set lease_token=null,lease_until=null,status=?,next_attempt_at=now()+(? * interval '1 second') where id=? and lease_token=?",status,delay,id,lease);if(changed>0&&delay==0)store.db.update("insert into outbox(execution_id) values(?)",id);});
    }
    void finish(UUID id,UUID lease,String status,String error){
        store.tx.executeWithoutResult(s->{
            int changed=store.db.update("update executions set status=?,error=?,finished_at=now(),lease_token=null,lease_until=null where id=? and lease_token=?",status,error,id,lease);
            if(changed>0&&!status.equals("SUCCEEDED"))store.db.update("update steps set status='SKIPPED',finished_at=now() where execution_id=? and status in ('PENDING','RETRYING','WAITING')",id);
            if(changed>0&&status.equals("FAILED"))store.db.update("update steps set status='FAILED',error=coalesce(error,?),finished_at=now() where execution_id=? and status='RUNNING'",error,id);
            if(changed>0&&!status.equals("SUCCEEDED")){
                store.db.update("update approvals set status='CANCELLED',decided_at=now() where execution_id=? and status='PENDING'",id);
                store.db.update("with recursive descendants as (select id from executions where parent_id=? union all select e.id from executions e join descendants d on e.parent_id=d.id) update executions set cancel_requested=true,next_attempt_at=now() where id in (select id from descendants) and status in ('QUEUED','RUNNING','WAITING','RETRYING')",id);
            }
            if(changed>0)store.db.update("update executions set next_attempt_at=now() where id=(select parent_id from executions where id=?) and status='WAITING'",id);
        });
    }
}
