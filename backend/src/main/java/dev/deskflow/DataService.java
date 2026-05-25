package dev.deskflow;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
class DataService {
    final Store store;
    DataService(Store store){this.store=store;}
    Map<String,Object> table(UUID owner,UUID id){return Store.clean(store.db.queryForMap("select * from data_tables where id=? and owner_id=?",id,owner));}
    JsonNode action(UUID owner,JsonNode config){
        UUID id=UUID.fromString(config.path("tableId").asText());table(owner,id);
        String operation=config.path("operation").asText();
        if(operation.equals("list"))return Json.M.valueToTree(Map.of("items",store.rows("select record_key,value,updated_at from data_records where table_id=? order by updated_at desc limit 100",id)));
        String key=Json.required(config,"key",200);
        if(operation.equals("get")){
            var result=store.rows("select record_key,value,updated_at from data_records where table_id=? and record_key=?",id,key);
            return result.isEmpty()?Json.M.valueToTree(Map.of("found",false)):Json.M.valueToTree(Json.map("found",true,"record",result.getFirst()));
        }
        if(operation.equals("delete"))return Json.M.valueToTree(Map.of("deleted",store.db.update("delete from data_records where table_id=? and record_key=?",id,key)>0));
        var value=Json.bounded(config.path("value"));
        store.db.update("insert into data_records(table_id,record_key,value) values(?,?,?::jsonb) on conflict(table_id,record_key) do update set value=excluded.value,updated_at=now()",id,key,Json.write(value));
        return Json.M.valueToTree(Map.of("key",key,"value",value,"saved",true));
    }
}
