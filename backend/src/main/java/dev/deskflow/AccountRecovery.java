package dev.deskflow;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.util.function.Function;

@RestController
@RequestMapping("/api/auth")
class AccountRecovery {
    final AuthController auth;final JdbcTemplate db;final TransactionTemplate tx;
    final JavaMailSender mail;final SocialProviders providers;final ThreadPoolTaskExecutor executor;final String from;
    AccountRecovery(AuthController auth,JdbcTemplate db,TransactionTemplate tx,JavaMailSender mail,SocialProviders providers,
        @Qualifier("accountEmailExecutor") ThreadPoolTaskExecutor executor,@Value("${app.account-mail-from:accounts@deskflow.local}") String from){
        this.auth=auth;this.db=db;this.tx=tx;this.mail=mail;this.providers=providers;this.executor=executor;this.from=from;
    }
    @Bean static ThreadPoolTaskExecutor accountEmailExecutor(){
        var executor=new ThreadPoolTaskExecutor();executor.setCorePoolSize(1);executor.setMaxPoolSize(2);executor.setQueueCapacity(100);executor.setThreadNamePrefix("account-email-");return executor;
    }
    static final Map<String,String> SENT=Map.of("message","If an account exists for this email, a reset link will be sent. Check your inbox and spam folder.");
    @PostMapping("/forgot-password") ResponseEntity<?> forgot(@RequestBody JsonNode body,HttpServletRequest req){
        auth.rate(req);String email=auth.email(body);
        // Queue every valid address so SMTP latency cannot reveal account existence.
        queue(()->{
            var ids=db.queryForList("select id from users where email=?",UUID.class,email);
            if(!ids.isEmpty())issue(ids.getFirst(),"RESET_PASSWORD",Map.of());
        });
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(SENT);
    }
    void queue(Runnable work){
        try{executor.execute(()->{try{work.run();}catch(Exception ignored){logDeliveryFailure();}});}
        catch(org.springframework.core.task.TaskRejectedException ignored){logDeliveryFailure();}
    }
    void issue(UUID user,String purpose,Map<String,Object> payload){
        String token=Secrets.token();String hash=Secrets.hash(token);
        String email=tx.execute(status->{
            var row=db.queryForMap("select email from users where id=? for update",user);
            db.update("delete from account_email_tokens where expires_at<now()");
            if(Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from account_email_tokens where user_id=? and purpose=? and created_at>now()-interval '1 minute')",Boolean.class,user,purpose)))return null;
            db.update("insert into account_email_tokens(token_hash,user_id,purpose,payload,expires_at) values(?,?,?,?::jsonb,now()+make_interval(mins=>?))",hash,user,purpose,Json.write(payload),purpose.equals("RESET_PASSWORD")?30:10);
            return (String)row.get("email");
        });
        if(email==null)return;
        var message=new SimpleMailMessage();message.setFrom(from);message.setTo(email);
        boolean reset=purpose.equals("RESET_PASSWORD");
        message.setSubject(reset?"Reset your DeskFlow password":"Confirm your DeskFlow sign-in");
        message.setText((reset?"Choose a new DeskFlow password. This link expires in 30 minutes.":"Confirm your email to finish connecting your sign-in provider. This link expires in 10 minutes.")
            +"\n\n"+providers.publicUrl+"/?auth="+(reset?"reset":"confirm-email")+"#token="+token+"\n\nIf you did not request this, ignore this email.");
        try{mail.send(message);}catch(org.springframework.mail.MailException error){db.update("delete from account_email_tokens where token_hash=?",hash);logDeliveryFailure();}
    }
    void logDeliveryFailure(){org.slf4j.LoggerFactory.getLogger(AccountRecovery.class).warn("Account email could not be delivered. Check SMTP configuration; no credentials or email contents were logged.");}
    <T>T consume(String token,String purpose,Function<Map<String,Object>,T> action){
        if(token==null||!token.matches("[A-Za-z0-9_-]{43}"))throw invalidLink();
        String hash=Secrets.hash(token);
        return tx.execute(status->{
            db.queryForObject("select pg_advisory_xact_lock(hashtext('deskflow-oauth'))",Object.class);
            var users=db.queryForList("select user_id from account_email_tokens where token_hash=? and purpose=?",UUID.class,hash,purpose);
            if(users.isEmpty())throw invalidLink();
            db.queryForMap("select id from users where id=? for update",users.getFirst());
            var rows=db.queryForList("select user_id,payload from account_email_tokens where token_hash=? and purpose=? and expires_at>now() for update",hash,purpose);
            if(rows.isEmpty())throw invalidLink();
            T result=action.apply(rows.getFirst());
            db.update("delete from account_email_tokens where user_id=?",users.getFirst());
            return result;
        });
    }
    ResponseStatusException invalidLink(){return new ResponseStatusException(HttpStatus.BAD_REQUEST,"This link is invalid or expired. Request a new one.");}
    @PostMapping("/reset-password") ResponseEntity<?> reset(@RequestBody JsonNode body,HttpServletRequest req){
        auth.rate(req);String hash=auth.passwords.encode(auth.password(body));
        consume(body.path("token").asText(),"RESET_PASSWORD",row->{
            UUID user=(UUID)row.get("user_id");
            verifyOwnership(user,false);
            db.update("update users set password_hash=?,email_verified_at=coalesce(email_verified_at,now()) where id=?",hash,user);
            db.update("delete from sessions where user_id=?",user);return true;
        });
        var session=req.getSession(false);if(session!=null)session.invalidate();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("ok",true));
    }
    /** A trusted mailbox owner supersedes unverified credentials from a possible pre-signup. */
    void verifyOwnership(UUID user,boolean previouslyAuthenticated){
        var row=db.queryForMap("select email_verified_at from users where id=? for update",user);
        if(row.get("email_verified_at")==null&&!previouslyAuthenticated){
            db.update("update users set password_hash=null where id=?",user);
            db.update("delete from sessions where user_id=?",user);
            db.update("delete from oauth_identities where user_id=?",user);
            db.update("delete from account_email_tokens where user_id=?",user);
        }
        db.update("update users set email_verified_at=coalesce(email_verified_at,now()) where id=?",user);
    }
}
