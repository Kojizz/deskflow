package dev.deskflow;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.ZoneId;
import java.util.*;
import org.springframework.scheduling.support.CronExpression;

final class Graph {
    record Node(String id,String kind,String label,JsonNode config) {}
    record Edge(String source,String target,String handle) {}
    final List<Node> nodes=new ArrayList<>();
    final List<Edge> edges=new ArrayList<>();
    final JsonNode settings;
    static final Set<String> KINDS=Set.of("project.command","project.read","project.git","manual","webhook","schedule","form","transform","condition","filter","switch","merge","format","collection","delay","approval","subworkflow","loop","table","http","email","ai","fail");
    static final Set<String> TERMINAL=Set.of("SUCCEEDED","SKIPPED","RECOVERED");
    Graph(JsonNode definition) {
        Json.bounded(definition);settings=definition.path("settings");
        if(settings.has("effectLimit"))range(settings,"effectLimit",0,1000);
        if(settings.has("allowedHosts")){if(!settings.path("allowedHosts").isArray()||settings.path("allowedHosts").size()>30)fail("Use an array of at most 30 allowed hosts");for(var host:settings.path("allowedHosts"))if(!host.isTextual()||!host.asText().matches("[A-Za-z0-9.-]{1,253}"))fail("Allowed hosts must be hostnames without a scheme or path");}
        if(!definition.path("nodes").isArray()||!definition.path("edges").isArray())fail("Workflow needs nodes and edges arrays");
        var ids=new HashSet<String>();
        for(var n:definition.path("nodes")){
            String id=Json.required(n,"id",64);
            if(!id.matches("[A-Za-z0-9_-]+")||!ids.add(id))fail("Node IDs must be unique letters, digits, underscores or hyphens");
            var data=n.path("data");String kind=Json.required(data,"kind",32);
            if(!KINDS.contains(kind))fail("Unsupported node type: "+kind);
            var c=data.path("config");if(!c.isObject())fail("Node configuration must be an object");
            validate(kind,c);nodes.add(new Node(id,kind,Json.required(data,"label",100),c));
        }
        if(nodes.isEmpty()||nodes.size()>60)fail("Use between 1 and 60 nodes");
        if(nodes.stream().filter(n->isTrigger(n.kind)).count()!=1)fail("Workflow requires exactly one trigger");
        if(definition.path("edges").size()>180)fail("Too many connections");
        var pairs=new HashSet<String>();
        for(var e:definition.path("edges")){
            String source=e.path("source").asText(),target=e.path("target").asText(),handle=Json.text(e,"sourceHandle","");
            if(!ids.contains(source)||!ids.contains(target)||source.equals(target))fail("Connection references an invalid node");
            if(!pairs.add(source+":"+target+":"+handle))fail("Duplicate connection");
            if(!handles(find(source)).contains(handle))fail("Invalid output handle '"+handle+"' on "+find(source).label);
            edges.add(new Edge(source,target,handle));
        }
        if(!incoming(trigger().id).isEmpty())fail("A trigger cannot have incoming connections");
        var remaining=new HashSet<>(ids);var seen=new HashSet<String>();
        while(!remaining.isEmpty()){
            var ready=remaining.stream().filter(id->incoming(id).stream().allMatch(e->seen.contains(e.source))).toList();
            if(ready.isEmpty())fail("Workflow contains a cycle");seen.addAll(ready);remaining.removeAll(ready);
        }
        var reachable=new HashSet<String>();reachable.add(trigger().id);
        for(int i=0;i<nodes.size();i++)for(var e:edges)if(reachable.contains(e.source))reachable.add(e.target);
        if(reachable.size()!=nodes.size())fail("Every step must be connected to the trigger");
    }
    static void validate(String kind,JsonNode c){
        if(c.has("maxAttempts"))range(c,"maxAttempts",1,5);
        if(c.has("retryDelaySeconds"))range(c,"retryDelaySeconds",1,60);
        if(!Set.of("stop","continue","route").contains(c.path("onError").asText("stop")))fail("Invalid error policy");
        switch(kind){
            case "project.command", "project.read", "project.git" -> ProjectSteps.validate(kind,c);
            case "schedule" -> {if(c.path("mode").asText("interval").equals("cron")){CronExpression.parse(Json.required(c,"cron",100));try{ZoneId.of(Json.text(c,"timezone","UTC"));}catch(java.time.DateTimeException e){fail("Invalid schedule timezone");}Schedules.next(c,java.time.Instant.now());}else range(c,"intervalMinutes",1,10080);}
            case "form" -> validateFields(c.path("fields"));
            case "transform" -> Json.bounded(c.path("output"));
            case "condition","filter" -> {Json.required(c,"field",250);if(!Set.of("equals","notEquals","contains","greaterThan","lessThan","exists").contains(c.path("operator").asText()))fail("Unknown condition operator");}
            case "switch" -> {
                Json.required(c,"field",250);if(!c.path("cases").isArray()||c.path("cases").isEmpty()||c.path("cases").size()>8)fail("Switch needs 1–8 cases");
                var labels=new HashSet<String>();for(var rule:c.path("cases")){String name=Json.required(rule,"name",30);if(!name.matches("[A-Za-z0-9_-]+")||Set.of("default","error").contains(name)||!labels.add(name))fail("Switch case names must be unique identifiers");if(!rule.has("value"))fail("Switch case needs a value");}
            }
            case "http" -> {Json.required(c,"url",2048);if(!Set.of("GET","POST","PUT","PATCH","DELETE","HEAD").contains(c.path("method").asText()))fail("Invalid HTTP method");}
            case "email" -> {Json.required(c,"to",254);Json.required(c,"subject",200);}
            case "delay" -> range(c,"seconds",1,86400);
            case "approval" -> Json.required(c,"title",200);
            case "subworkflow","loop" -> {try{UUID.fromString(Json.required(c,"workflowId",36));}catch(Exception e){fail("Select a valid child workflow");}if(kind.equals("loop")&&!c.has("items"))fail("Loop needs an items expression");}
            case "table" -> {try{UUID.fromString(Json.required(c,"tableId",36));}catch(Exception e){fail("Select a valid data table");}if(!Set.of("get","list","upsert","delete").contains(c.path("operation").asText()))fail("Unknown table operation");}
            case "format" -> {if(!Set.of("uppercase","lowercase","trim","number","round","dateIso","jsonParse","jsonStringify").contains(c.path("operation").asText()))fail("Unknown formatter operation");if(!c.has("value"))fail("Formatter needs a value");}
            case "collection" -> {if(!Set.of("count","pluck","unique","sort","limit","sum","filter").contains(c.path("operation").asText()))fail("Unknown collection operation");if(!c.has("items"))fail("Collection needs items");}
            case "ai" -> {Json.required(c,"url",2048);Json.required(c,"model",100);Json.required(c,"prompt",10000);if(c.path("credentialId").asText().isBlank())fail("AI action needs a saved bearer credential");}
            default -> {}
        }
    }
    static void validateFields(JsonNode fields){
        if(!fields.isArray()||fields.isEmpty()||fields.size()>20)fail("A form needs 1–20 fields");var names=new HashSet<String>();
        for(var f:fields){String name=Json.required(f,"name",40);if(!name.matches("[A-Za-z][A-Za-z0-9_]*")||!names.add(name))fail("Form field names must be unique identifiers");Json.required(f,"label",100);if(!Set.of("text","email","number","checkbox").contains(f.path("type").asText()))fail("Invalid form field type");}
    }
    static void range(JsonNode c,String key,int min,int max){int value=c.path(key).asInt(Integer.MIN_VALUE);if(value<min||value>max)fail(key+" must be "+min+"–"+max);}
    static List<String> handles(Node node){
        var result=new ArrayList<String>();
        if(Set.of("condition","approval").contains(node.kind)){result.add("true");result.add("false");}
        else if(node.kind.equals("switch")){for(var rule:node.config.path("cases"))result.add(rule.path("name").asText());result.add("default");}
        else result.add("");
        if(node.config.path("onError").asText().equals("route"))result.add("error");return result;
    }
    static boolean active(Node source,String status,JsonNode output,String handle){
        if(status.equals("SKIPPED"))return false;
        if(status.equals("RECOVERED"))return source.config.path("onError").asText().equals("route")?handle.equals("error"):handle.isEmpty();
        if(!status.equals("SUCCEEDED")||handle.equals("error"))return false;
        return switch(source.kind){case "condition"->handle.equals(Boolean.toString(output.path("result").asBoolean()));case "approval"->handle.equals(Boolean.toString(output.path("approved").asBoolean()));case "switch"->handle.equals(output.path("route").asText());case "filter"->output.path("passed").asBoolean();default->handle.isEmpty();};
    }
    Node find(String id){return nodes.stream().filter(n->n.id.equals(id)).findFirst().orElseThrow();}
    Node trigger(){return nodes.stream().filter(n->isTrigger(n.kind)).findFirst().orElseThrow();}
    List<Edge> incoming(String id){return edges.stream().filter(e->e.target.equals(id)).toList();}
    static boolean isTrigger(String kind){return Set.of("manual","webhook","schedule","form").contains(kind);}
    static void fail(String message){throw new IllegalArgumentException(message);}
}
