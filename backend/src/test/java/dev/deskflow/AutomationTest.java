package dev.deskflow;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.Flow;
import static org.junit.jupiter.api.Assertions.*;

class AutomationTest {
    com.fasterxml.jackson.databind.JsonNode action(String kind,String config,String input){return PureActions.execute(new Graph.Node("test",kind,"Test",Json.read(config)),Json.read("{\"input\":"+input+",\"steps\":{},\"parents\":{}}"));}
    @Test void formatterPreservesNumericAndJsonTypes(){
        assertEquals(12.35,action("format","{\"operation\":\"round\",\"value\":12.345,\"decimals\":2}","{}").path("value").asDouble());
        assertEquals("ADA",action("format","{\"operation\":\"uppercase\",\"value\":\"{{input.name}}\"}","{\"name\":\"Ada\"}").path("value").asText());
        assertTrue(action("format","{\"operation\":\"jsonParse\",\"value\":\"{\\\"ok\\\":true}\"}","{}").path("value").path("ok").asBoolean());
    }
    @Test void collectionsHandleTypedArraysAndBoundWork(){
        assertEquals(6,action("collection","{\"operation\":\"sum\",\"items\":\"{{input.items}}\"}","{\"items\":[1,2,3]}").path("value").asInt());
        assertEquals(2,action("collection","{\"operation\":\"unique\",\"items\":[1,1,2]}","{}").path("items").size());
        assertEquals("Ada",action("collection","{\"operation\":\"pluck\",\"field\":\"name\",\"items\":[{\"name\":\"Ada\"}]}","{}").path("items").path(0).asText());
        var items=Json.M.createArrayNode();for(int i=0;i<501;i++)items.add(i);
        assertThrows(IllegalArgumentException.class,()->action("collection",Json.write(Map.of("operation","count","items",items)),"{}"));
    }
    @Test void arrayExpressionsKeepTypes(){
        var result=Templates.render(Json.read("\"{{input.orders.0.total}}\""),Json.read("{\"input\":{\"orders\":[{\"total\":42}]}}"));assertTrue(result.isNumber());assertEquals(42,result.asInt());
    }
    @Test void cronUsesTimezoneAndRejectsSubMinuteFrequency(){
        assertEquals(Instant.parse("2026-10-18T04:00:00Z"),Schedules.next(Json.read("{\"mode\":\"cron\",\"cron\":\"0 0 9 * * *\",\"timezone\":\"Asia/Almaty\"}"),Instant.parse("2026-10-17T12:00:00Z")));
        assertThrows(IllegalArgumentException.class,()->Schedules.next(Json.read("{\"mode\":\"cron\",\"cron\":\"* * * * * *\",\"timezone\":\"UTC\"}"),Instant.now()));
    }
    @Test void graphRejectsInvalidSwitchHandlesAndTimezone(){
        String nodes=GraphTest.node("start","manual","{}")+","+GraphTest.node("route","switch","{\"field\":\"input.kind\",\"cases\":[{\"name\":\"a\",\"value\":1}]}")+","+GraphTest.node("end","merge","{}");
        assertThrows(IllegalArgumentException.class,()->new Graph(Json.read("{\"nodes\":["+nodes+"],\"edges\":["+GraphTest.edge("start","route")+",{\"source\":\"route\",\"target\":\"end\",\"sourceHandle\":\"missing\"}]}")));
        assertThrows(IllegalArgumentException.class,()->Graph.validate("schedule",Json.read("{\"mode\":\"cron\",\"cron\":\"0 * * * * *\",\"timezone\":\"not-a-zone\"}")));
    }
    @Test void responseSubscriberCancelsBeforeOversizedAllocation(){
        var subscriber=new LimitedBody();var cancelled=new java.util.concurrent.atomic.AtomicBoolean();subscriber.onSubscribe(new Flow.Subscription(){public void request(long n){}public void cancel(){cancelled.set(true);}});
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[65000])));subscriber.onNext(List.of(ByteBuffer.wrap(new byte[1000])));
        assertTrue(cancelled.get());assertEquals(65000,subscriber.output.size());assertTrue(subscriber.result.isCompletedExceptionally());
    }
}
