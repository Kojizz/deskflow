package dev.deskflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.SimpleMailMessage;
import java.net.*;
import java.net.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

@Service
class Actions {
    final ProjectSteps projectSteps;
    final Store store;final JavaMailSender mail;final DataService data;final EffectGuard guard;
    final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    Actions(Store store,JavaMailSender mail,DataService data,EffectGuard guard,ProjectSteps projectSteps){this.projectSteps=projectSteps;this.store=store;this.mail=mail;this.data=data;this.guard=guard;}
    static class Failure extends RuntimeException {
        final boolean retry;
        Failure(String message,boolean retry){super(message);this.retry=retry;}
    }
    JsonNode execute(Graph.Node node,JsonNode context,UUID owner,UUID run){
        if(PureActions.supports(node.kind()))return PureActions.execute(node,context);
        return switch(node.kind()){
            case "project.command", "project.read", "project.git" -> projectSteps.execute(node,context,owner,run);
            case "http" -> http(node,context,owner,run);
            case "email" -> {var c=Templates.render(node.config(),context);guard.reserve(run,node,"email:"+c.path("to").asText());yield email(c);}
            case "table" -> {
                var c=Templates.render(node.config(),context);
                if(Set.of("upsert","delete").contains(c.path("operation").asText()))guard.reserve(run,node,"table:"+c.path("tableId").asText()+"/"+c.path("key").asText());
                yield data.action(owner,c);
            }
            case "ai" -> {
                ObjectNode c=node.config().deepCopy();c.put("method","POST");
                c.set("body",Json.M.valueToTree(Map.of("model",c.path("model").asText(),"messages",List.of(Map.of("role","user","content",c.path("prompt").asText())))));
                var response=http(new Graph.Node(node.id(),"ai",node.label(),c),context,owner,run).path("body");
                var content=response.path("choices").path(0).path("message").path("content");
                if(!content.isTextual())throw new Failure("AI provider returned no text completion",false);
                yield Json.M.valueToTree(Json.map("text",content.asText(),"usage",response.path("usage")));
            }
            default -> throw new Failure("Unsupported action",false);
        };
    }
    static URI publicUri(String target){
        try{
            URI uri=URI.create(target);
            if(!Set.of("http","https").contains(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getFragment()!=null)throw new Failure("Use a public HTTP(S) URL without credentials or fragments",false);
            if(uri.getPort()!=-1&&uri.getPort()!=80&&uri.getPort()!=443)throw new Failure("HTTP actions allow only ports 80 and 443",false);
            for(var address:InetAddress.getAllByName(uri.getHost())){
                byte[] b=address.getAddress();int a=b[0]&255;int second=b.length>1?b[1]&255:0;
                if(address.isAnyLocalAddress()||address.isLoopbackAddress()||address.isLinkLocalAddress()||address.isSiteLocalAddress()||address.isMulticastAddress()
                    ||(b.length==16&&(b[0]&0xfe)==0xfc)
                    ||(b.length==4&&(a==0||a>=224||(a==100&&second>=64&&second<=127)||(a==198&&(second==18||second==19)))))throw new Failure("Private or reserved network destinations are blocked",false);
            }
            return uri;
        }catch(Failure e){throw e;}catch(Exception e){throw new Failure("Invalid or unresolvable URL",false);}
    }
    JsonNode http(Graph.Node node,JsonNode context,UUID owner,UUID run){
        var c=node.config();var uri=publicUri(Templates.render(c.path("url"),context).asText());
        guard.destination(run,uri.getHost());
        String method=c.path("method").asText("GET");
        var builder=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).header("Accept","application/json").header("Idempotency-Key",run+":"+node.id());
        String credential=c.path("credentialId").asText("");
        if(!credential.isBlank()){
            var values=store.db.queryForList("select encrypted_value from credentials where id=? and owner_id=?",String.class,UUID.fromString(credential),owner);
            if(values.isEmpty())throw new Failure("Credential was removed or is unavailable",false);
            builder.header("Authorization","Bearer "+store.secrets.decrypt(values.getFirst()));
        }
        var headers=Templates.render(c.path("headers"),context);
        if(headers.isObject())headers.fields().forEachRemaining(e->{
            if(Set.of("host","connection","content-length","authorization","idempotency-key","upgrade","expect").contains(e.getKey().toLowerCase(Locale.ROOT)))throw new Failure("Use saved credentials for Authorization; reserved headers cannot be overridden",false);
            builder.header(e.getKey(),e.getValue().asText());
        });
        var body=HttpRequest.BodyPublishers.noBody();
        if(!Set.of("GET","HEAD").contains(method)&&c.has("body")){
            byte[] bytes=Json.write(Templates.render(c.path("body"),context)).getBytes(StandardCharsets.UTF_8);
            if(bytes.length>65536)throw new Failure("HTTP request body exceeds 64 KB",false);
            builder.header("Content-Type","application/json");body=HttpRequest.BodyPublishers.ofByteArray(bytes);
        }
        guard.reserve(run,node,method+" "+uri);
        try{
            var response=client.send(builder.method(method,body).build(),info->new LimitedBody());
            // Bodies are bounded by the subscriber before completion, including error responses.
            int code=response.statusCode();
            if(response.body().length>65536)throw new Failure("HTTP response exceeds 64 KB",false);
            if(code<200||code>=300)throw new Failure("HTTP action returned status "+code,code==429||code>=500);
            String raw=new String(response.body(),StandardCharsets.UTF_8);JsonNode output;
            try{output=Json.read(raw);}catch(Exception e){output=Json.M.getNodeFactory().textNode(raw);}
            if(c.path("requireOk").asBoolean()&&output.has("ok")&&!output.path("ok").asBoolean())throw new Failure("Provider rejected the action: "+output.path("error").asText("unknown error"),false);
            return Json.M.valueToTree(Json.map("status",code,"body",output));
        }catch(Failure e){throw e;}catch(InterruptedException e){Thread.currentThread().interrupt();throw new Failure("HTTP request interrupted",true);}catch(IOException e){boolean size=e.toString().contains("64 KB")||(e.getCause()!=null&&e.getCause().toString().contains("64 KB"));throw new Failure(size?"HTTP response exceeds 64 KB":"HTTP connection failed or timed out",!size);}
    }
    JsonNode email(JsonNode c){
        String to=c.path("to").asText();
        if(!to.matches("[^\\s@,;\\r\\n]+@[^\\s@,;\\r\\n]+\\.[^\\s@,;\\r\\n]+"))throw new Failure("Invalid email recipient",false);
        var message=new SimpleMailMessage();message.setFrom("automation@deskflow.local");message.setTo(to);message.setSubject(c.path("subject").asText());message.setText(c.path("body").asText(""));
        try{mail.send(message);}catch(Exception e){throw new Failure("Email delivery failed",true);}
        return Json.M.valueToTree(Map.of("delivered",true,"to",to));
    }
}
