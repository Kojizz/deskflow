package dev.deskflow;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Configuration
class QueueConfiguration {
    static final String QUEUE="deskflow.executions";
    @Bean Queue executionQueue() { return new Queue(QUEUE,true); }
}

@Component
@ConditionalOnProperty(name="app.role",havingValue="api")
class Dispatch {
    final Store store;final RabbitTemplate rabbit;
    @Value("${app.publisher-enabled:true}") boolean publisherEnabled;
    Dispatch(Store store,RabbitTemplate rabbit) { this.store=store;this.rabbit=rabbit; }
    @Scheduled(fixedDelay=500)
    void publish() {
        if(!publisherEnabled) return;
        for(var event:store.db.queryForList("select id,execution_id from outbox order by id limit 25")) {
            try {
                var correlation=new CorrelationData(UUID.randomUUID().toString());
                rabbit.convertAndSend("",QueueConfiguration.QUEUE,event.get("execution_id").toString(),message->{message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);return message;},correlation);
                var confirm=correlation.getFuture().get(5,TimeUnit.SECONDS);
                if(!confirm.isAck() || correlation.getReturned()!=null) return;
                store.db.update("delete from outbox where id=?",event.get("id"));
            }catch(Exception e){return;}
        }
    }
    @Scheduled(fixedDelay=5000)
    void schedules() {
        for(UUID id:store.db.queryForList("select id from workflows where active and not archived and next_run_at<=now() limit 25",UUID.class)) {
            store.tx.executeWithoutResult(s->{
                var wf=Store.clean(store.db.queryForMap("select * from workflows where id=? for update",id));
                var due=store.db.queryForObject("select active and next_run_at<=now() from workflows where id=?",Boolean.class,id);
                if(!Boolean.TRUE.equals(due))return;
                var graph=new Graph((JsonNode)wf.get("definition"));
                if(!graph.trigger().kind().equals("schedule"))return;
                var input=graph.trigger().config().path("input");if(!input.isContainerNode())input=Json.M.createObjectNode();
                store.enqueue(wf,input,"schedule","schedule:"+wf.get("next_run_at"));
                store.db.update("update workflows set next_run_at=? where id=?",java.sql.Timestamp.from(Schedules.next(graph.trigger().config(),java.time.Instant.now())),id);
            });
        }
    }
}

@Component
@ConditionalOnProperty(name="app.worker-enabled",havingValue="true")
class Worker {
    final Engine engine;final Store store;
    Worker(Engine engine,Store store) { this.engine=engine;this.store=store; }
    @RabbitListener(queues=QueueConfiguration.QUEUE)
    void receive(String id) { engine.advance(UUID.fromString(id)); }
    @Scheduled(fixedDelay=2000)
    void recover() {
        for(UUID id:store.db.queryForList("select id from executions where status in ('QUEUED','RUNNING','RETRYING','WAITING') and next_attempt_at<=now() and (lease_until is null or lease_until<now()) order by created_at limit 20",UUID.class)) engine.advance(id);
    }
}
