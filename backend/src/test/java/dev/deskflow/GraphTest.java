package dev.deskflow;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GraphTest {
    static String node(String id,String kind,String config) {return "{\"id\":\""+id+"\",\"data\":{\"label\":\""+id+"\",\"kind\":\""+kind+"\",\"config\":"+config+"}}";}
    static String edge(String a,String b) {return "{\"source\":\""+a+"\",\"target\":\""+b+"\"}";}
    @Test void acceptsConnectedDag() {assertEquals(2,new Graph(Json.read("{\"nodes\":["+node("start","manual","{}")+","+node("map","transform","{\"output\":{}}")+"],\"edges\":["+edge("start","map")+"]}")).nodes.size());}
    @Test void rejectsCycles() {assertThrows(IllegalArgumentException.class,()->new Graph(Json.read("{\"nodes\":["+node("start","manual","{}")+","+node("a","transform","{\"output\":{}}")+","+node("b","transform","{\"output\":{}}")+"],\"edges\":["+edge("start","a")+","+edge("a","b")+","+edge("b","a")+"]}")));}
    @Test void rejectsDisconnectedSteps() {assertThrows(IllegalArgumentException.class,()->new Graph(Json.read("{\"nodes\":["+node("start","manual","{}")+","+node("map","transform","{\"output\":{}}")+"],\"edges\":[]}")));}
    @Test void rejectsExtraTriggers() {assertThrows(IllegalArgumentException.class,()->new Graph(Json.read("{\"nodes\":["+node("a","manual","{}")+","+node("b","webhook","{}")+"],\"edges\":[]}")));}
    @Test void templatesPreserveNumericTypes() {
        var input=Json.read("{\"input\":{\"total\":123,\"name\":\"Ada\"}}");
        var result=Templates.render(Json.read("{\"amount\":\"{{input.total}}\",\"greeting\":\"Hi {{input.name}}\"}"),input);
        assertTrue(result.path("amount").isNumber());assertEquals("Hi Ada",result.path("greeting").asText());
    }
    @Test void missingVariablesFailClearly(){assertThrows(IllegalArgumentException.class,()->Templates.render(Json.read("\"{{input.missing}}\""),Json.read("{}")));}
    @Test void payloadLimitCountsUtf8Bytes(){var value=Json.M.createObjectNode().put("value","字".repeat(23000));assertThrows(IllegalArgumentException.class,()->Json.bounded(value));}
    @Test void numericCondition(){assertTrue(Templates.condition(Json.read("{\"field\":\"input.total\",\"operator\":\"greaterThan\",\"value\":100}"),Json.read("{\"input\":{\"total\":150}}")));}
    @Test void blocksPrivateHttpDestinations(){
        for(String url:new String[]{"http://127.0.0.1","http://169.254.169.254","http://10.0.0.1","http://[::1]","file:///etc/passwd","http://example.com:5432"}) assertThrows(Actions.Failure.class,()->Actions.publicUri(url));
    }
    @Test void encryptionAuthenticatesAndRandomizes(){
        var s=new Secrets(java.util.Base64.getEncoder().encodeToString(new byte[32]));var a=s.encrypt("my-private-token");var b=s.encrypt("my-private-token");
        assertNotEquals(a,b);assertEquals("my-private-token",s.decrypt(a));assertFalse(a.contains("my-private-token"));
        assertThrows(IllegalStateException.class,()->s.decrypt(a.substring(0,a.length()-3)+"AAA"));
    }
}
