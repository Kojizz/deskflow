package dev.deskflow;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
class EffectGuard {
    final Store store;
    EffectGuard(Store store){this.store=store;}
    void reserve(UUID run,Graph.Node node,String target){
        store.tx.executeWithoutResult(s->{
            var execution=store.db.queryForMap("select coalesce(root_id,id) as root,owner_id,cancel_requested from executions where id=?",run);
            UUID root=(UUID)execution.get("root");
            var claimed=store.db.queryForList("update executions set effect_count=effect_count+1 where id=? and effect_count<effect_limit and not cancel_requested and status in ('QUEUED','RUNNING','WAITING','RETRYING') returning id",root);
            if(claimed.isEmpty()||Boolean.TRUE.equals(execution.get("cancel_requested")))throw new Actions.Failure("Impact budget exhausted or execution cancelled; no action was dispatched",false);
            store.db.update("insert into effect_events(root_id,execution_id,node_id,kind,target) values(?,?,?,?,?)",root,run,node.id(),node.kind(),store.redact((UUID)execution.get("owner_id"),target));
        });
    }
    void destination(UUID run,String host){
        var definitions=store.db.queryForList("select definition::text from executions where id=(select coalesce(root_id,id) from executions where id=?)",String.class,run);
        if(definitions.isEmpty())return;
        JsonNode hosts=Json.read(definitions.getFirst()).path("settings").path("allowedHosts");
        if(hosts.isArray()&&!hosts.isEmpty()){
            boolean allowed=false;for(var h:hosts)if(h.asText().equalsIgnoreCase(host))allowed=true;
            if(!allowed)throw new Actions.Failure("Destination is outside this workflow's allowed hosts",false);
        }
    }
}
