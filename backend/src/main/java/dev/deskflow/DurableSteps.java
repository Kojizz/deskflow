package dev.deskflow;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

@Service
class DurableSteps {
    record Outcome(JsonNode output,int waitSeconds){boolean waiting(){return waitSeconds>0;}}
    final Store store;
    DurableSteps(Store store){this.store=store;}
    static boolean supports(String kind){return Set.of("delay","approval","subworkflow","loop").contains(kind);}
    Outcome poll(Graph.Node node,JsonNode context,Map<String,Object> run,JsonNode state){
        UUID id=(UUID)run.get("id"),owner=(UUID)run.get("owner_id");
        var c=node.config();
        if(node.kind().equals("delay")){
            Instant resume=state.has("resumeAt")?Instant.parse(state.path("resumeAt").asText()):Instant.now().plusSeconds(c.path("seconds").asLong());
            if(!state.has("resumeAt"))save(id,node.id(),Json.M.valueToTree(Map.of("resumeAt",resume.toString())));
            long remaining=Duration.between(Instant.now(),resume).toMillis();
            return remaining>0?new Outcome(null,(int)Math.max(1,(remaining+999)/1000)):new Outcome(Json.M.valueToTree(Map.of("resumedAt",resume.toString())),0);
        }
        if(node.kind().equals("approval")){
            String title=Templates.render(c.path("title"),context).asText();
            store.db.update("insert into approvals(id,execution_id,node_id,owner_id,title) values(?,?,?,?,?) on conflict(execution_id,node_id) do nothing",UUID.randomUUID(),id,node.id(),owner,title);
            var approval=store.rows("select * from approvals where execution_id=? and node_id=?",id,node.id()).getFirst();
            if(approval.get("status").equals("PENDING"))return new Outcome(null,60);
            return new Outcome(Json.M.valueToTree(Json.map("approved",approval.get("status").equals("APPROVED"),"decidedAt",approval.get("decided_at"))),0);
        }
        int depth=((Number)run.get("depth")).intValue();
        if(depth>=5)throw new Actions.Failure("Subworkflow depth limit (5) reached",false);
        if(!state.has("children")){
            JsonNode items=node.kind().equals("loop")?Templates.render(c.path("items"),context):Json.M.createArrayNode().add(context.path("input"));
            if(!items.isArray()||items.size()>20)throw new Actions.Failure("Loop needs an array with at most 20 items",false);
            var child=store.workflow(owner,UUID.fromString(c.path("workflowId").asText()));
            if(Boolean.TRUE.equals(child.get("archived")))throw new Actions.Failure("Child workflow is archived",false);
            var children=Json.M.createArrayNode();
            store.tx.executeWithoutResult(tx->{
                int index=0;
                for(var item:items){
                    JsonNode input;
                    if(node.kind().equals("loop"))input=Json.M.valueToTree(Json.map("item",item,"index",index,"source",context.path("input")));
                    else input=c.has("input")?Templates.render(c.path("input"),context):context.path("input");
                    var selectedChild = new LinkedHashMap<>(child);
                    Object instance = c.hasNonNull("instanceId") ? UUID.fromString(c.path("instanceId").asText()) : run.get("instance_id");
                    if (instance != null) { selectedChild.put("_instance",instance); }
                    UUID childId=store.enqueue(selectedChild,input,node.kind(),"child:"+id+":"+node.id()+":"+index);
                    store.db.update("update executions set parent_id=?,parent_node_id=?,child_index=?,root_id=?,depth=? where id=?",id,node.id(),index,run.get("root_id")==null?id:run.get("root_id"),depth+1,childId);
                    children.add(childId.toString());index++;
                }
                save(id,node.id(),Json.M.valueToTree(Map.of("children",children)));
            });
            if(children.isEmpty())return new Outcome(Json.M.valueToTree(Map.of("items",List.of(),"count",0)),0);
            return new Outcome(null,2);
        }
        var children=store.rows("select id,status,error from executions where parent_id=? and parent_node_id=? order by child_index",id,node.id());
        if(children.stream().anyMatch(r->Set.of("FAILED","CANCELLED").contains(r.get("status")))){
            store.db.update("update executions set cancel_requested=true,next_attempt_at=now() where parent_id=? and parent_node_id=? and status in ('QUEUED','RUNNING','WAITING','RETRYING')",id,node.id());
            throw new Actions.Failure("A child execution failed or was cancelled; inspect its run before resuming",false);
        }
        if(children.stream().anyMatch(r->!r.get("status").equals("SUCCEEDED")))return new Outcome(null,2);
        var outputs=new ArrayList<Object>();
        for(var child:children){
            var results=new LinkedHashMap<String,Object>();
            for(var step:store.rows("select node_id,output from steps where execution_id=? and status in ('SUCCEEDED','RECOVERED')",child.get("id")))results.put((String)step.get("node_id"),step.get("output"));
            outputs.add(Json.map("executionId",child.get("id"),"steps",results));
        }
        return new Outcome(Json.M.valueToTree(Map.of("items",outputs,"count",outputs.size())),0);
    }
    void save(UUID run,String node,JsonNode state){store.db.update("update steps set state=?::jsonb where execution_id=? and node_id=?",Json.write(state),run,node);}
}
