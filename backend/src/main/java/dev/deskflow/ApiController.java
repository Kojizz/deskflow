package dev.deskflow;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController
@RequestMapping("/api")
class ApiController {
    final Store store;
    ApiController(Store store) { this.store=store; }
    UUID owner(HttpServletRequest req) { return (UUID)req.getAttribute("userId"); }
    @GetMapping("/workflows") Object workflows(@RequestParam(defaultValue="false") boolean archived,HttpServletRequest r) {
        return store.rows("select w.id,w.name,w.description,w.active,w.version,w.updated_at,w.definition,w.archived,jsonb_array_length(w.definition->'nodes') as node_count,(select status from executions e where e.workflow_id=w.id order by e.created_at desc limit 1) as last_status from workflows w where owner_id=? and archived=? order by updated_at desc limit 100",owner(r),archived);
    }
    @GetMapping("/workflows/{id}") Object workflow(@PathVariable UUID id,HttpServletRequest r) { return store.workflow(owner(r),id); }
    @PostMapping("/workflows") Object create(@RequestBody JsonNode body,HttpServletRequest r) { return store.save(owner(r),null,body); }
    @PutMapping("/workflows/{id}") Object update(@PathVariable UUID id,@RequestBody JsonNode body,HttpServletRequest r) { return store.save(owner(r),id,body); }
    @PostMapping("/workflows/validate") Object validate(@RequestBody JsonNode body,HttpServletRequest r) {
        var g=new Graph(body);store.credentialRefs(owner(r),g);return Map.of("valid",true,"nodes",g.nodes.size());
    }
    @PostMapping("/workflows/{id}/execute") Object run(@PathVariable UUID id,@RequestBody JsonNode body,@RequestHeader(value="Idempotency-Key",required=false) String key,HttpServletRequest r) {
        return Map.of("id",store.enqueue(store.workflow(owner(r),id),body,"manual",key));
    }
    @GetMapping("/executions") Object executions(@RequestParam(required=false) UUID workflowId,@RequestParam(defaultValue="") String status,@RequestParam(defaultValue="") String search,@RequestParam(defaultValue="0") int offset,HttpServletRequest r) {
        if(workflowId!=null) store.workflow(owner(r),workflowId);
        return store.rows("select id,workflow_id,workflow_name,version,status,trigger_type,created_at,started_at,finished_at,error,parent_id,effect_count,effect_limit from executions where owner_id=? and (?::uuid is null or workflow_id=?::uuid) and (?='' or status=?) and workflow_name ilike ? order by created_at desc limit 100 offset ?",owner(r),workflowId,workflowId,status,status,"%"+search+"%",Math.max(0,offset));
    }
    @GetMapping("/executions/{id}") Object execution(@PathVariable UUID id,HttpServletRequest r) { return store.execution(owner(r),id); }
    @PostMapping("/executions/{id}/cancel") Object cancel(@PathVariable UUID id,HttpServletRequest r) {
        store.execution(owner(r),id);
        store.db.update("update executions set cancel_requested=true,next_attempt_at=now() where id=? and status in ('QUEUED','RUNNING','RETRYING','WAITING')",id);
        return Map.of("ok",true);
    }
    @PostMapping("/executions/{id}/replay") Object replay(@PathVariable UUID id,HttpServletRequest r) {
        var execution=Store.clean(store.db.queryForMap("select * from executions where id=? and owner_id=?",id,owner(r)));
        var wf=Json.map("id",execution.get("workflow_id"),"owner_id",owner(r),"name",execution.get("workflow_name"),"version",execution.get("version"),"definition",execution.get("definition"));
        return Map.of("id",store.enqueue(wf,(JsonNode)execution.get("input"),"replay",null));
    }
    @GetMapping("/credentials") Object credentials(HttpServletRequest r) { return store.rows("select id,name,created_at from credentials where owner_id=? order by created_at desc",owner(r)); }
    @PostMapping("/credentials") Object credential(@RequestBody JsonNode body,HttpServletRequest r) {
        UUID id=UUID.randomUUID();String name=Json.required(body,"name",80);String secret=Json.required(body,"value",4096);
        if(secret.length()<8) throw new IllegalArgumentException("Token must contain at least 8 characters");
        store.db.update("insert into credentials(id,owner_id,name,encrypted_value) values(?,?,?,?)",id,owner(r),name,store.secrets.encrypt(secret));
        return Map.of("id",id,"name",name);
    }
    @DeleteMapping("/credentials/{id}") Object removeCredential(@PathVariable UUID id,HttpServletRequest r) {
        // Historical execution versions may still reference a token. Keep deletion explicit and let missing-token actions fail safely.
        int removed=store.db.update("delete from credentials where id=? and owner_id=?",id,owner(r));
        if(removed==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Credential not found");
        return Map.of("ok",true);
    }
    @GetMapping("/stats") Object stats(HttpServletRequest r) {
        UUID owner=owner(r);
        return Json.map("workflows",store.db.queryForObject("select count(*) from workflows where owner_id=?",Long.class,owner),
          "active",store.db.queryForObject("select count(*) from workflows where owner_id=? and active",Long.class,owner),
          "executions",store.db.queryForObject("select count(*) from executions where owner_id=?",Long.class,owner),
          "succeeded",store.db.queryForObject("select count(*) from executions where owner_id=? and status='SUCCEEDED'",Long.class,owner),
          "failed",store.db.queryForObject("select count(*) from executions where owner_id=? and status='FAILED'",Long.class,owner));
    }
}
