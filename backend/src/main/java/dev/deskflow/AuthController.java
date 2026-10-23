package dev.deskflow;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/auth")
class AuthController {
    final JdbcTemplate db; final BCryptPasswordEncoder passwords; final TransactionTemplate tx; final boolean secure;
    final ConcurrentHashMap<String,long[]> limits=new ConcurrentHashMap<>();
    AuthController(JdbcTemplate db,BCryptPasswordEncoder passwords,TransactionTemplate tx,@Value("${app.cookie-secure}") boolean secure) { this.db=db;this.passwords=passwords;this.tx=tx;this.secure=secure; }
    void rate(HttpServletRequest req) {
        long now=System.currentTimeMillis();
        limits.entrySet().removeIf(e->now-e.getValue()[0]>60000);
        var count=limits.compute(req.getRemoteAddr(),(k,v)->v==null?new long[]{now,1}:new long[]{v[0],v[1]+1});
        if(count[1]>20) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Too many attempts; wait a minute");
    }
    String email(JsonNode body) {
        String e=Json.required(body,"email",254).toLowerCase(Locale.ROOT);
        if(!e.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw new IllegalArgumentException("Enter a valid email");
        return e;
    }
    String password(JsonNode body) {
        String p=body.path("password").asText("");
        if(p.length()<10 || p.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72) throw new IllegalArgumentException("Password must be 10–72 bytes");
        return p;
    }
    @PostMapping("/register") ResponseEntity<?> register(@RequestBody JsonNode body,HttpServletRequest req) {
        rate(req); var id=UUID.randomUUID(); var mail=email(body); var name=Json.required(body,"name",80); var hashed=passwords.encode(password(body));
        return tx.execute(s->{db.update("insert into users(id,email,name,password_hash) values(?,?,?,?)",id,mail,name,hashed); return session(id);});
    }
    @PostMapping("/login") ResponseEntity<?> login(@RequestBody JsonNode body,HttpServletRequest req) {
        rate(req); var mail=email(body); var pass=password(body);
        var users=db.queryForList("select id,password_hash from users where email=?",mail);
        if(users.isEmpty() || users.getFirst().get("password_hash")==null || !passwords.matches(pass,(String)users.getFirst().get("password_hash"))) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Email or password is incorrect");
        return session((UUID)users.getFirst().get("id"));
    }
    ResponseEntity<?> session(UUID id) {
        String token=Secrets.token();
        db.update("delete from sessions where expires_at<now()");
        db.update("insert into sessions(token_hash,user_id,expires_at) values(?,?,now()+interval '7 days')",Secrets.hash(token),id);
        String cookie=ResponseCookie.from("deskflow_session",token).httpOnly(true).secure(secure).sameSite("Strict").path("/").maxAge(604800).build().toString();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.SET_COOKIE,cookie).body(user(id));
    }
    @GetMapping("/me") Map<String,Object> me(HttpServletRequest req) { return user((UUID)req.getAttribute("userId")); }
    Map<String,Object> user(UUID id) { return db.queryForMap("select id,email,name from users where id=?",id); }
    @PostMapping("/logout") ResponseEntity<?> logout(HttpServletRequest req) {
        if(req.getCookies()!=null) for(var c:req.getCookies()) if(c.getName().equals("deskflow_session")) db.update("delete from sessions where token_hash=?",Secrets.hash(c.getValue()));
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE,ResponseCookie.from("deskflow_session","").path("/").httpOnly(true).sameSite("Strict").maxAge(0).build().toString()).body(Map.of("ok",true));
    }
}
