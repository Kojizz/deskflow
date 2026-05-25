package dev.deskflow;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

final class Json {
    static final ObjectMapper M = new ObjectMapper().findAndRegisterModules();
    static JsonNode read(String s) {
        try { return M.readTree(s); } catch (Exception e) { throw new IllegalArgumentException("Invalid JSON"); }
    }
    static String write(Object o) {
        try { return M.writeValueAsString(o); } catch (Exception e) { throw new IllegalArgumentException("Cannot serialize JSON"); }
    }
    static String text(JsonNode n, String key, String fallback) { return n.path(key).asText(fallback); }
    static String required(JsonNode n, String key, int max) {
        String s = text(n,key,"").trim();
        if (s.isEmpty() || s.length()>max) throw new IllegalArgumentException(key+" must be between 1 and "+max+" characters");
        return s;
    }
    static JsonNode bounded(JsonNode node) {
        if (node == null || !node.isContainerNode()) throw new IllegalArgumentException("Expected a JSON object or array");
        if (write(node).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65536) throw new IllegalArgumentException("JSON payload exceeds 64 KB");
        return node;
    }
    static Map<String,Object> map(Object... pairs) {
        var result = new LinkedHashMap<String,Object>();
        for (int i=0;i<pairs.length;i+=2) result.put((String)pairs[i], pairs[i+1]);
        return result;
    }
}
