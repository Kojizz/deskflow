package dev.deskflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.math.*;
import java.time.*;
import java.util.*;

final class PureActions {
    static boolean supports(String kind){return Set.of("transform","condition","filter","switch","merge","format","collection","fail").contains(kind)||Graph.isTrigger(kind);}
    static JsonNode execute(Graph.Node node,JsonNode context){
        var c=node.config();
        if(Graph.isTrigger(node.kind()))return context.path("input");
        return switch(node.kind()){
            case "transform" -> Json.bounded(Templates.render(c.path("output"),context));
            case "condition" -> Json.M.valueToTree(Map.of("result",Templates.condition(c,context)));
            case "filter" -> Json.M.valueToTree(Map.of("passed",Templates.condition(c,context),"input",context.path("input")));
            case "switch" -> {
                var value=Templates.required(context,c.path("field").asText());String route="default";
                for(var rule:c.path("cases"))if(value.equals(Templates.render(rule.path("value"),context))){route=rule.path("name").asText();break;}
                yield Json.M.valueToTree(Map.of("route",route,"value",value));
            }
            case "merge" -> Json.M.valueToTree(Map.of("parents",context.path("parents")));
            case "format" -> format(c,context);
            case "collection" -> collection(c,context);
            case "fail" -> throw new Actions.Failure(Templates.render(c.path("message"),context).asText("Stopped by workflow"),false);
            default -> throw new IllegalArgumentException("Not a pure action");
        };
    }
    static JsonNode format(JsonNode c,JsonNode context){
        var value=Templates.render(c.path("value"),context);Object result;
        try{
            result=switch(c.path("operation").asText()){
                case "uppercase"->value.asText().toUpperCase(Locale.ROOT);
                case "lowercase"->value.asText().toLowerCase(Locale.ROOT);
                case "trim"->value.asText().trim();
                case "number"->new BigDecimal(value.asText());
                case "round"->new BigDecimal(value.asText()).setScale(Math.max(0,Math.min(8,c.path("decimals").asInt(2))),RoundingMode.HALF_UP);
                case "dateIso"->OffsetDateTime.parse(value.asText()).toInstant().toString();
                case "jsonParse"->Json.read(value.asText());
                case "jsonStringify"->Json.write(value);
                default->throw new IllegalArgumentException("Unknown formatter");
            };
        }catch(Exception e){throw new IllegalArgumentException("Cannot format value as "+c.path("operation").asText());}
        return Json.M.valueToTree(Map.of("value",result));
    }
    static JsonNode collection(JsonNode c,JsonNode context){
        var value=Templates.render(c.path("items"),context);
        if(!value.isArray()||value.size()>500)throw new IllegalArgumentException("Collection requires an array of at most 500 items");
        var items=new ArrayList<JsonNode>();value.forEach(items::add);String field=c.path("field").asText("");
        switch(c.path("operation").asText()){
            case "count" -> {return Json.M.valueToTree(Map.of("count",items.size()));}
            case "sum" -> {BigDecimal total=BigDecimal.ZERO;for(var item:items){var number=field.isBlank()?item:Templates.required(item,field);if(!number.isNumber())throw new IllegalArgumentException("Sum requires numbers");total=total.add(number.decimalValue());}return Json.M.valueToTree(Map.of("value",total));}
            case "pluck" -> items=new ArrayList<>(items.stream().map(item->Templates.required(item,field)).toList());
            case "unique" -> {var seen=new HashSet<JsonNode>();items.removeIf(item->!seen.add(field.isBlank()?item:Templates.required(item,field)));}
            case "sort" -> items.sort((a,b)->{var x=field.isBlank()?a:Templates.required(a,field);var y=field.isBlank()?b:Templates.required(b,field);return x.isNumber()&&y.isNumber()?x.decimalValue().compareTo(y.decimalValue()):x.asText().compareTo(y.asText());});
            case "limit" -> items=new ArrayList<>(items.subList(0,Math.min(items.size(),Math.max(0,c.path("limit").asInt(10)))));
            case "filter" -> {var expected=Templates.render(c.path("value"),context);items.removeIf(item->!Templates.lookup(item,field).equals(expected));}
            default -> throw new IllegalArgumentException("Unknown collection operation");
        }
        return Json.M.valueToTree(Map.of("items",items,"count",items.size()));
    }
}
