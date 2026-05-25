package dev.deskflow;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.HtmlUtils;
import java.math.BigDecimal;
import java.util.*;

@RestController
class FormController {
    final Store store;
    FormController(Store store){this.store=store;}
    Map<String,Object> lookup(UUID id,String token){
        var rows=store.rows("select * from workflows where id=? and webhook_token=? and active and not archived",id,token);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Active form not found");
        var wf=rows.getFirst();if(!new Graph((JsonNode)wf.get("definition")).trigger().kind().equals("form"))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Form trigger not configured");return wf;
    }
    static String escape(String value){return HtmlUtils.htmlEscape(value);}
    String page(String title,String content){return "<!doctype html><html lang='en'><meta charset='utf-8'><meta name='viewport' content='width=device-width, initial-scale=1'><title>"+escape(title)+" · DeskFlow</title><style>body{font:16px system-ui;background:#f4f7f0;color:#294632;margin:0;padding:50px 20px}main{max-width:480px;margin:auto;background:white;padding:35px;border:1px solid #dce5d4;border-radius:16px}h1{font-size:28px}label{display:block;margin:22px 0;font-size:14px}input{display:block;width:100%;box-sizing:border-box;padding:12px;margin-top:8px;border:1px solid #cedbc4;border-radius:7px;font:inherit}input[type=checkbox]{width:auto}button{background:#2b513d;color:white;border:0;border-radius:8px;padding:13px 22px;cursor:pointer}small{color:#889a7c}p{line-height:1.7}</style><main><small>DESKFLOW FORMS</small><h1>"+escape(title)+"</h1>"+content+"</main></html>";}
    @GetMapping(value="/forms/{id}/{token}",produces=MediaType.TEXT_HTML_VALUE)
    String form(@PathVariable UUID id,@PathVariable String token){
        var wf=lookup(id,token);var config=new Graph((JsonNode)wf.get("definition")).trigger().config();var html=new StringBuilder("<form method='post'>");
        for(var f:config.path("fields")){String type=f.path("type").asText();html.append("<label>").append(escape(f.path("label").asText())).append("<input name='").append(escape(f.path("name").asText())).append("' type='").append(type).append("' maxlength='2000' ").append(type.equals("number")?"step='any' ":"").append(f.path("required").asBoolean()?"required":"").append("></label>");}
        html.append("<button>Submit</button></form>");return page(config.path("title").asText(wf.get("name").toString()),html.toString());
    }
    JsonNode input(JsonNode config,Map<String,String> values){
        var input=Json.M.createObjectNode();
        for(var field:config.path("fields")){
            String name=field.path("name").asText(),value=values.getOrDefault(name,"").trim(),type=field.path("type").asText();
            if(field.path("required").asBoolean()&&value.isEmpty())throw new IllegalArgumentException(field.path("label").asText()+" is required");
            if(value.length()>2000)throw new IllegalArgumentException("Form value is too long");
            if(type.equals("checkbox")){boolean checked=Set.of("on","true","1").contains(value);if(field.path("required").asBoolean()&&!checked)throw new IllegalArgumentException(field.path("label").asText()+" is required");input.put(name,checked);}
            else if(type.equals("number")){try{if(value.isBlank())input.putNull(name);else input.put(name,new BigDecimal(value));}catch(Exception e){throw new IllegalArgumentException("Invalid number for "+name);}}
            else{if(type.equals("email")&&!value.isBlank()&&!value.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))throw new IllegalArgumentException("Invalid email");input.put(name,value);}
        }
        return Json.bounded(input);
    }
    @PostMapping(value="/forms/{id}/{token}",consumes=MediaType.APPLICATION_FORM_URLENCODED_VALUE,produces=MediaType.TEXT_HTML_VALUE)
    String submit(@PathVariable UUID id,@PathVariable String token,@RequestParam Map<String,String> values){var wf=lookup(id,token);var c=new Graph((JsonNode)wf.get("definition")).trigger().config();var run=store.enqueue(wf,input(c,values),"form",null);return page("Thank you.","<p>Your response has been received.</p><small>Reference "+run+"</small>");}
}
