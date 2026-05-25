package dev.deskflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.regex.*;

final class Templates {
    static final Pattern SLOT=Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*}}");
    static JsonNode lookup(JsonNode context,String path) {
        JsonNode result=context;
        for(String key:path.split("\\.")) result=result.isArray()&&key.matches("[0-9]+")?result.path(Integer.parseInt(key)):result.path(key);
        return result;
    }
    static JsonNode render(JsonNode value,JsonNode context) {
        if(value.isObject()) {
            ObjectNode result=Json.M.createObjectNode();
            value.fields().forEachRemaining(e->result.set(e.getKey(),render(e.getValue(),context))); return result;
        }
        if(value.isArray()) {
            ArrayNode result=Json.M.createArrayNode();for(var item:value) result.add(render(item,context));return result;
        }
        if(!value.isTextual()) return value;
        var matcher=SLOT.matcher(value.asText());
        if(matcher.matches()) return required(context,matcher.group(1));
        var result=new StringBuffer();
        while(matcher.find()) {
            var resolved=required(context,matcher.group(1));
            matcher.appendReplacement(result,Matcher.quoteReplacement(resolved.isContainerNode()?Json.write(resolved):resolved.asText()));
        }
        matcher.appendTail(result);return TextNode.valueOf(result.toString());
    }
    static JsonNode required(JsonNode context,String path) {
        var value=lookup(context,path);
        if(value.isMissingNode()) throw new IllegalArgumentException("Variable not found: "+path);
        return value;
    }
    static boolean condition(JsonNode config,JsonNode context) {
        var value=lookup(context,config.path("field").asText());
        var expected=render(config.path("value"),context);
        return switch(config.path("operator").asText()) {
            case "exists" -> !value.isMissingNode() && !value.isNull();
            case "equals" -> value.equals(expected);
            case "notEquals" -> !value.equals(expected);
            case "contains" -> !value.isMissingNode() && value.asText().contains(expected.asText());
            case "greaterThan" -> value.isNumber() && expected.isNumber() && value.decimalValue().compareTo(expected.decimalValue())>0;
            case "lessThan" -> value.isNumber() && expected.isNumber() && value.decimalValue().compareTo(expected.decimalValue())<0;
            default -> throw new IllegalArgumentException("Invalid condition");
        };
    }
}
