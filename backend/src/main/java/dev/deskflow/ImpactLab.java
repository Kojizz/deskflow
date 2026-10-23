package dev.deskflow;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
class ImpactLab {
    final Store store;
    ImpactLab(Store store){this.store=store;}
    static class Preview {
        final List<Map<String,Object>> effects=new ArrayList<>();
        final List<String> assumptions=new ArrayList<>();
        int visits;
        final Set<String> allowedHosts=new HashSet<>();
    }
    Map<String,Object> simulate(UUID owner,JsonNode definition,JsonNode input,JsonNode mocks){
        Json.bounded(input);Json.bounded(mocks);var preview=new Preview();
        definition.path("settings").path("allowedHosts").forEach(h->preview.allowedHosts.add(h.asText().toLowerCase(Locale.ROOT)));
        var result=walk(owner,definition,input,mocks,"",0,preview);
        int limit=definition.path("settings").path("effectLimit").asInt(100);
        result.put("effects",preview.effects);result.put("assumptions",preview.assumptions);result.put("effectLimit",limit);result.put("effectCount",preview.effects.size());
        result.put("overBudget",preview.effects.size()>limit);result.put("sideEffectsDispatched",0);
        if(preview.effects.size()>limit)result.put("status","BUDGET_EXCEEDED");
        return result;
    }
    Map<String,Object> walk(UUID owner,JsonNode definition,JsonNode input,JsonNode mocks,String prefix,int depth,Preview preview){
        if(depth>5)throw new IllegalArgumentException("Preview subworkflow depth limit reached");
        var graph=new Graph(definition);var statuses=new LinkedHashMap<String,String>();var outputs=new LinkedHashMap<String,JsonNode>();var trace=new ArrayList<Map<String,Object>>();
        while(statuses.size()<graph.nodes.size()){
            boolean progressed=false;
            for(var node:graph.nodes){
                if(statuses.containsKey(node.id()))continue;
                var incoming=graph.incoming(node.id());if(!incoming.stream().allMatch(e->statuses.containsKey(e.source())))continue;
                progressed=true;if(++preview.visits>2000)throw new IllegalArgumentException("Preview exceeds 2000 evaluated steps");
                String path=prefix+node.id();String state="SUCCEEDED";String error=null;JsonNode output=Json.M.nullNode();
                var parents=Json.M.createObjectNode();
                for(var edge:incoming)if(Graph.active(graph.find(edge.source()),statuses.get(edge.source()),outputs.get(edge.source()),edge.handle()))parents.set(edge.source(),outputs.get(edge.source()));
                if(!incoming.isEmpty()&&parents.isEmpty())state=incoming.stream().anyMatch(e->Set.of("FAILED","NEEDS_MOCK","BLOCKED").contains(statuses.get(e.source())))?"BLOCKED":"SKIPPED";
                else{
                    var context=Json.M.createObjectNode();context.set("input",input);context.set("steps",Json.M.valueToTree(outputs));context.set("parents",parents);
                    try{
                        var c=Templates.render(node.config(),context);
                        JsonNode mock=mocks.has(path)?mocks.path(path):mocks.has(node.id())?mocks.path(node.id()):c.path("pinnedOutput");
                        if(PureActions.supports(node.kind())){if(!mock.isMissingNode()&&!Graph.isTrigger(node.kind())){output=mock;preview.assumptions.add(path+": pinned or mocked output replaces evaluation");}else output=PureActions.execute(node,context);}
                        else if(node.kind().equals("delay")){output=Json.M.valueToTree(Map.of("resumedAt","SIMULATED"));preview.assumptions.add(path+": delay of "+c.path("seconds").asInt()+" seconds is not elapsed during preview");}
                        else if(node.kind().equals("approval")){output=mock.isMissingNode()?Json.M.valueToTree(Map.of("approved",true)):mock;preview.assumptions.add(path+": approval decision is a fixture, not a real decision");}
                        else if(Set.of("subworkflow","loop").contains(node.kind())){
                            var child=store.workflow(owner,UUID.fromString(c.path("workflowId").asText()));var results=new ArrayList<Object>();
                            JsonNode items=node.kind().equals("loop")?c.path("items"):Json.M.createArrayNode().add(c.has("input")?c.path("input"):input);
                            if(!items.isArray()||items.size()>20)throw new IllegalArgumentException("Preview loop needs at most 20 items");
                            int index=0;
                            for(var item:items){JsonNode childInput=node.kind().equals("loop")?Json.M.valueToTree(Json.map("item",item,"index",index,"source",input)):item;
                                var childResult=walk(owner,(JsonNode)child.get("definition"),childInput,mocks,path+"["+index+"]/",depth+1,preview);
                                if(!childResult.get("status").equals("SUCCEEDED"))throw new IllegalArgumentException("Child preview incomplete: "+childResult.get("status"));
                                results.add(Map.of("steps",childResult.get("outputs")));index++;
                            }
                            output=Json.M.valueToTree(Map.of("items",results,"count",results.size()));
                        }else{
                            if (node.kind().startsWith("project.")) {
                                UUID instanceId = UUID.fromString(definition.path("settings").path("instanceId").asText());
                                var instance = store.instance(owner,instanceId);
                                preview.effects.add(Json.map("node",path,"kind",node.kind(),"instanceId",instanceId,"fingerprint",instance.get("state_fingerprint"),"argv",c.path("argv"),"operation",c.path("operation"),"path",c.path("path")));
                                if (mock.isMissingNode()) {
                                    state = "NEEDS_MOCK";
                                    error = "Supply a project fixture; no project process or file read was performed";
                                } else {
                                    output = mock;
                                }
                            } else if(Set.of("http","ai").contains(node.kind())){
                                var uri=java.net.URI.create(c.path("url").asText());
                                if(!preview.allowedHosts.isEmpty()&&(uri.getHost()==null||!preview.allowedHosts.contains(uri.getHost().toLowerCase(Locale.ROOT))))throw new IllegalArgumentException("Destination is outside this workflow's allowed hosts");
                                preview.effects.add(Json.map("node",path,"kind",node.kind(),"target",c.path("method").asText("POST")+" "+c.path("url").asText(),"body",c.path("body")));
                                if(mock.isMissingNode()){state="NEEDS_MOCK";error="Supply a mock output for "+path+"; no network request was sent";}
                                else{output=mock;preview.assumptions.add(path+": external response is a fixture");}
                            }else if(node.kind().equals("email")){
                                preview.effects.add(Json.map("node",path,"kind","email","target",c.path("to").asText(),"subject",c.path("subject"),"body",c.path("body")));
                                output=mock.isMissingNode()?Json.M.valueToTree(Map.of("delivered",false,"simulated",true,"to",c.path("to").asText())):mock;
                            }else if(node.kind().equals("table")){
                                boolean writes=Set.of("upsert","delete").contains(c.path("operation").asText());
                                if(writes){preview.effects.add(Json.map("node",path,"kind","table","target",c.path("operation").asText()+" "+c.path("tableId").asText()+"/"+c.path("key").asText(),"value",c.path("value")));output=mock.isMissingNode()?c.path("operation").asText().equals("delete")?Json.M.valueToTree(Map.of("deleted",true,"simulated",true)):Json.M.valueToTree(Json.map("saved",true,"simulated",true,"key",c.path("key"),"value",c.path("value"))):mock;preview.assumptions.add(path+": table write result is simulated; record existence is not checked");}
                                else if(mock.isMissingNode()){state="NEEDS_MOCK";error="Supply table read fixture for "+path;}
                                else output=mock;
                            }
                        }
                    }catch(Exception e){error=e.getMessage()==null?"Preview could not evaluate step":e.getMessage();state=node.config().path("onError").asText("stop").equals("stop")?"FAILED":"RECOVERED";output=Json.M.valueToTree(Map.of("error",Map.of("message",error)));}
                }
                statuses.put(node.id(),state);outputs.put(node.id(),output);trace.add(Json.map("node",path,"label",node.label(),"kind",node.kind(),"status",state,"output",output,"error",error));
            }
            if(!progressed)throw new IllegalArgumentException("Preview cannot resolve graph");
        }
        String status=statuses.containsValue("FAILED")?"FAILED":statuses.containsValue("NEEDS_MOCK")?"NEEDS_MOCK":statuses.containsValue("BLOCKED")?"FAILED":"SUCCEEDED";
        return Json.map("status",status,"trace",trace,"outputs",outputs);
    }
    Object compare(UUID owner,JsonNode before,JsonNode after,JsonNode input,JsonNode mocks){
        var baseline=simulate(owner,before,input,mocks);var candidate=simulate(owner,after,input,mocks);
        var a=(Map<?,?>)baseline.get("outputs");var b=(Map<?,?>)candidate.get("outputs");var keys=new TreeSet<String>();a.keySet().forEach(k->keys.add(k.toString()));b.keySet().forEach(k->keys.add(k.toString()));
        var changes=new ArrayList<Object>();for(String key:keys)if(!Objects.equals(a.get(key),b.get(key)))changes.add(Json.map("node",key,"before",a.get(key),"after",b.get(key)));
        return Json.map("baseline",baseline,"candidate",candidate,"changes",changes,"effectsChanged",!Objects.equals(baseline.get("effects"),candidate.get("effects")),"sideEffectsDispatched",0);
    }
}
