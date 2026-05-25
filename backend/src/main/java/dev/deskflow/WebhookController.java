package dev.deskflow;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController
class WebhookController {
    final Store store;
    WebhookController(Store store) { this.store=store; }
    @PostMapping("/hooks/{id}/{token}") ResponseEntity<?> hook(@PathVariable UUID id,@PathVariable String token,@RequestBody JsonNode body,@RequestHeader(value="Idempotency-Key",required=false) String key) {
        var list=store.rows("select * from workflows where id=? and webhook_token=? and active and not archived",id,token);
        if(list.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Active webhook not found");
        var wf=list.getFirst();var g=new Graph((JsonNode)wf.get("definition"));
        if(!g.trigger().kind().equals("webhook")) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Webhook trigger not configured");
        UUID run=store.enqueue(wf,body,"webhook",key);
        return ResponseEntity.accepted().body(Map.of("id",run));
    }
}
