package dev.deskflow;

import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.userinfo.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.core.user.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;

@Component
class SocialProviders implements ClientRegistrationRepository,Iterable<ClientRegistration> {
    final Map<String,ClientRegistration> registrations=new LinkedHashMap<>();
    final String publicUrl;
    SocialProviders(Environment env,@Value("${app.public-url}") String publicUrl) {
        var uri=URI.create(publicUrl);
        if(uri.getHost()==null || !Set.of("http","https").contains(uri.getScheme()) || uri.getRawQuery()!=null || uri.getRawFragment()!=null || uri.getUserInfo()!=null || !Set.of("","/").contains(uri.getPath())) throw new IllegalArgumentException("APP_PUBLIC_URL must be an HTTP(S) origin");
        this.publicUrl=publicUrl.replaceAll("/+$","");
        for(String provider:List.of("google","github")) {
            String key=provider.toUpperCase(Locale.ROOT);
            String id=env.getProperty(key+"_CLIENT_ID","").trim(),secret=env.getProperty(key+"_CLIENT_SECRET","").trim();
            if(id.isEmpty() || secret.isEmpty()) continue;
            var builder=(provider.equals("google")?CommonOAuth2Provider.GOOGLE:CommonOAuth2Provider.GITHUB).getBuilder(provider)
                .clientId(id).clientSecret(secret).redirectUri(this.publicUrl+"/api/auth/oauth2/callback/"+provider);
            if(provider.equals("github")) builder.scope("read:user","user:email");
            registrations.put(provider,builder.build());
        }
    }
    public ClientRegistration findByRegistrationId(String id){return registrations.get(id);}
    public Iterator<ClientRegistration> iterator(){return registrations.values().iterator();}
}

@RestController
@RequestMapping("/api/auth/oauth2")
class SocialLogin {
    record PendingLink(String provider,String subject,String email,String name,UUID userId,long expiresAt){}
    static final String PENDING_LINK="deskflow.pendingLink";
    final SocialProviders providers; final AuthController auth; final JdbcTemplate db; final TransactionTemplate tx; final AccountRecovery recovery;
    SocialLogin(SocialProviders providers,AuthController auth,JdbcTemplate db,TransactionTemplate tx,AccountRecovery recovery){this.providers=providers;this.auth=auth;this.db=db;this.tx=tx;this.recovery=recovery;}
    @GetMapping("/providers") Map<String,Boolean> providers(){return Map.of("google",providers.findByRegistrationId("google")!=null,"github",providers.findByRegistrationId("github")!=null);}
    @GetMapping("/unavailable") void unavailable(HttpServletResponse res)throws IOException{res.sendRedirect(providers.publicUrl+"/?oauth_error=unavailable#get-started");}
    @GetMapping("/connections") List<String> connections(HttpServletRequest req){return db.queryForList("select provider from oauth_identities where user_id=? order by provider",String.class,req.getAttribute("userId"));}
    PendingLink pending(HttpSession session){
        try{
            if(session!=null && session.getAttribute(PENDING_LINK) instanceof PendingLink link && link.expiresAt()>System.currentTimeMillis())return link;
        }catch(IllegalStateException ignored){}
        throw new ResponseStatusException(HttpStatus.GONE,"This connection request expired. Continue with your provider again.");
    }
    @GetMapping("/pending-link") Map<String,Object> pendingLink(HttpServletRequest req,HttpServletResponse res){
        res.setHeader("Cache-Control","no-store");
        PendingLink link;
        try{link=pending(req.getSession(false));}catch(ResponseStatusException error){return Map.of("active",false);}
        boolean hasPassword=Boolean.TRUE.equals(db.queryForObject("select password_hash is not null from users where id=?",Boolean.class,link.userId()));
        return Map.of("active",true,"provider",link.provider(),"email",link.email(),"hasPassword",hasPassword,
            "alreadySignedIn",link.userId().equals(req.getAttribute("userId")),
            "existingProviders",db.queryForList("select provider from oauth_identities where user_id=? order by provider",String.class,link.userId()));
    }
    @PostMapping("/confirm-link") ResponseEntity<?> confirmLink(@RequestBody com.fasterxml.jackson.databind.JsonNode body,HttpServletRequest req){
        auth.rate(req);
        var session=req.getSession(false);
        if(session==null)throw new ResponseStatusException(HttpStatus.GONE,"This connection request expired. Continue with your provider again.");
        synchronized(session){
            var link=pending(session);
            if(!link.userId().equals(req.getAttribute("userId"))){
                if(link.provider().equals("google"))throw new ResponseStatusException(HttpStatus.CONFLICT,"Confirm your email to finish Google sign-in. No DeskFlow password is needed.");
                var hash=db.queryForObject("select password_hash from users where id=?",String.class,link.userId());
                if(hash==null || !auth.passwords.matches(auth.password(body),hash))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Your DeskFlow password is incorrect. Please try again.");
            }
            ResponseEntity<?> response;
            try{response=tx.execute(status->{UUID id=resolve(link.provider(),link.subject(),link.email(),link.name(),link.userId());return auth.session(id);});}
            catch(OAuth2AuthenticationException error){throw new ResponseStatusException(HttpStatus.CONFLICT,"This provider is already connected to an account. Use your existing sign-in method.");}
            session.invalidate();
            return response;
        }
    }
    @PostMapping("/cancel-link") Map<String,Boolean> cancelLink(HttpServletRequest req){
        var session=req.getSession(false);if(session!=null)session.invalidate();return Map.of("ok",true);
    }
    @PostMapping("/email-link") ResponseEntity<?> emailLink(HttpServletRequest req){
        auth.rate(req);var link=pending(req.getSession(false));
        if(!link.provider().equals("google"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Continue with your existing sign-in method.");
        recovery.queue(()->{if(link.expiresAt()>System.currentTimeMillis())recovery.issue(link.userId(),"LINK_PROVIDER",Json.map("provider",link.provider(),"subject",link.subject(),"email",link.email(),"name",link.name()));});
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("message","A confirmation link will be sent to your email. No DeskFlow password is needed."));
    }
    @PostMapping("/confirm-email") ResponseEntity<?> confirmEmail(@RequestBody com.fasterxml.jackson.databind.JsonNode body,HttpServletRequest req){
        auth.rate(req);
        try{var response=recovery.consume(body.path("token").asText(),"LINK_PROVIDER",row->{
            UUID id=(UUID)row.get("user_id");var proof=Json.read(row.get("payload").toString());
            recovery.verifyOwnership(id,id.equals(req.getAttribute("userId")));
            resolve(proof.path("provider").asText(),proof.path("subject").asText(),proof.path("email").asText(),proof.path("name").asText(),id);
            return auth.session(id);
        });var session=req.getSession(false);if(session!=null)session.invalidate();return response;}
        catch(OAuth2AuthenticationException error){throw new ResponseStatusException(HttpStatus.CONFLICT,"This provider is already connected. Continue with your existing sign-in method.");}
    }
    @GetMapping("/start/{provider}") void start(@PathVariable String provider,@RequestParam(defaultValue="false") boolean link,
        @RequestParam(defaultValue="false") boolean confirm,@RequestParam(defaultValue="login") String intent,
        HttpServletRequest req,HttpServletResponse res)throws IOException{
        auth.rate(req);
        if(providers.findByRegistrationId(provider)==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"This sign-in provider is not configured");
        UUID user=(UUID)req.getAttribute("userId");
        if(link && user==null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Sign in before connecting an account");
        var old=req.getSession(false);PendingLink confirmation=confirm?pending(old):null;
        if(confirm && (link || db.queryForList("select subject from oauth_identities where user_id=? and provider=?",confirmation.userId(),provider).isEmpty()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose an account already connected to your workspace.");
        if(old!=null)old.invalidate();
        var session=req.getSession(true);session.setMaxInactiveInterval(600);
        session.setAttribute("oauthIntent",intent.equals("signup")?"signup":"signin");
        if(confirmation!=null)session.setAttribute("confirmPending",confirmation);
        if(user!=null){
            session.setAttribute("originUser",user);
            if(link)session.setAttribute("linkUser",user);
            if(req.getCookies()!=null)for(var cookie:req.getCookies()) if(cookie.getName().equals("deskflow_session")) session.setAttribute("linkSession",Secrets.hash(cookie.getValue()));
        }
        res.sendRedirect("/api/auth/oauth2/authorize/"+provider);
    }
    OAuth2UserService<OAuth2UserRequest,OAuth2User> githubUserService(){
        var delegate=new DefaultOAuth2UserService();
        var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        var client=RestClient.builder().requestFactory(factory).build();
        return request->{
            var user=delegate.loadUser(request);
            if(!request.getClientRegistration().getRegistrationId().equals("github"))return user;
            com.fasterxml.jackson.databind.JsonNode emails;
            try{emails=client.get().uri("https://api.github.com/user/emails").headers(h->{h.setBearerAuth(request.getAccessToken().getTokenValue());h.setAccept(List.of(MediaType.APPLICATION_JSON));}).retrieve().body(com.fasterxml.jackson.databind.JsonNode.class);}
            catch(org.springframework.web.client.RestClientException error){throw new OAuth2AuthenticationException(new OAuth2Error("provider_unavailable"),"Provider email lookup failed");}
            String email=null;
            if(emails!=null && emails.isArray())for(var item:emails)if(item.path("verified").asBoolean()&&(email==null||item.path("primary").asBoolean()))email=item.path("email").asText();
            if(email==null)throw new OAuth2AuthenticationException("verified_email_required");
            var attributes=new HashMap<String,Object>(user.getAttributes());attributes.put("email",email);attributes.put("email_verified",true);
            return new DefaultOAuth2User(user.getAuthorities(),attributes,"id");
        };
    }
    UUID resolve(String provider,String subject,String email,String name,UUID linkUser){
        return resolve(provider,subject,email,name,linkUser,false,null);
    }
    UUID resolve(String provider,String subject,String email,String name,UUID linkUser,boolean trustedGoogle,UUID authenticatedOwner){
        if(subject==null||subject.isBlank()||subject.length()>255 || email==null || email.length()>254 || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))throw new OAuth2AuthenticationException("verified_email_required");
        String mail=email.toLowerCase(Locale.ROOT);
        return tx.execute(status->{
            // Only authoritative Google mailbox claims can resolve an email collision directly.
            db.queryForObject("select pg_advisory_xact_lock(hashtext('deskflow-oauth'))",Object.class);
            var existing=db.queryForList("select user_id from oauth_identities where provider=? and subject=?",UUID.class,provider,subject);
            if(!existing.isEmpty()){
                if(linkUser!=null&&!existing.getFirst().equals(linkUser))throw new OAuth2AuthenticationException("identity_in_use");
                if(trustedGoogle && mail.equals(db.queryForObject("select email from users where id=?",String.class,existing.getFirst())))recovery.verifyOwnership(existing.getFirst(),true);
                return existing.getFirst();
            }
            UUID id=linkUser;
            if(id==null){
                var matching=db.queryForList("select id from users where email=?",UUID.class,mail);
                if(!matching.isEmpty()){
                    if(!trustedGoogle)throw new OAuth2AuthenticationException("account_exists");
                    id=matching.getFirst();recovery.verifyOwnership(id,id.equals(authenticatedOwner));
                    if(!db.queryForList("select subject from oauth_identities where user_id=? and provider=?",id,provider).isEmpty())throw new OAuth2AuthenticationException("provider_already_linked");
                }else{
                    id=UUID.randomUUID();String display=name==null||name.isBlank()?mail.split("@")[0]:name;display=display.substring(0,Math.min(80,display.length()));
                    db.update("insert into users(id,email,name,password_hash) values(?,?,?,null)",id,mail,display);
                    if(trustedGoogle)recovery.verifyOwnership(id,true);
                }
            }else if(!db.queryForList("select subject from oauth_identities where user_id=? and provider=?",id,provider).isEmpty())throw new OAuth2AuthenticationException("provider_already_linked");
            db.update("insert into oauth_identities(provider,subject,user_id) values(?,?,?)",provider,subject,id);
            return id;
        });
    }
    boolean authoritativeGoogle(String provider,Map<String,Object> attrs){
        if(!provider.equals("google") || !Boolean.TRUE.equals(attrs.get("email_verified")))return false;
        String email=Objects.toString(attrs.get("email"),"").toLowerCase(Locale.ROOT);
        return email.endsWith("@gmail.com") || attrs.get("hd") instanceof String domain&&!domain.isBlank();
    }
    void success(HttpServletRequest req,HttpServletResponse res,Authentication authentication)throws IOException{
        try{
            var token=(OAuth2AuthenticationToken)authentication;var attrs=token.getPrincipal().getAttributes();
            String provider=token.getAuthorizedClientRegistrationId();
            if(!Boolean.TRUE.equals(attrs.get("email_verified")))throw new OAuth2AuthenticationException("verified_email_required");
            UUID link=null,authenticatedOwner=null;var session=req.getSession(false);
            if(session!=null && session.getAttribute("originUser") instanceof UUID origin && session.getAttribute("linkSession") instanceof String originToken){
                var valid=db.queryForList("select user_id from sessions where token_hash=? and expires_at>now()",UUID.class,originToken);
                if(!valid.isEmpty()&&valid.getFirst().equals(origin))authenticatedOwner=origin;
            }
            if(session!=null&&session.getAttribute("linkUser")!=null){
                link=(UUID)session.getAttribute("linkUser");
                var valid=db.queryForList("select user_id from sessions where token_hash=? and expires_at>now()",UUID.class,session.getAttribute("linkSession"));
                if(valid.isEmpty()||!valid.getFirst().equals(link))throw new OAuth2AuthenticationException("link_expired");
            }
            Object subject=attrs.get(provider.equals("google")?"sub":"id");
            UUID id;
            if(session!=null && session.getAttribute("confirmPending") instanceof PendingLink confirmation){
                if(confirmation.expiresAt()<=System.currentTimeMillis())throw new OAuth2AuthenticationException("link_expired");
                var existing=db.queryForList("select user_id from oauth_identities where provider=? and subject=?",UUID.class,provider,subject==null?null:subject.toString());
                if(existing.isEmpty() || !existing.getFirst().equals(confirmation.userId()))throw new OAuth2AuthenticationException("confirmation_account_mismatch");
                id=resolve(confirmation.provider(),confirmation.subject(),confirmation.email(),confirmation.name(),confirmation.userId());link=id;
            }else{
                try{id=resolve(provider,subject==null?null:subject.toString(),(String)attrs.get("email"),(String)attrs.get("name"),link,authoritativeGoogle(provider,attrs),authenticatedOwner);}
                catch(OAuth2AuthenticationException error){
                    if(link!=null || !error.getError().getErrorCode().equals("account_exists"))throw error;
                    String email=((String)attrs.get("email")).toLowerCase(Locale.ROOT);
                    UUID target=db.queryForObject("select id from users where email=?",UUID.class,email);
                    if(session!=null)session.invalidate();
                    var confirmation=req.getSession(true);confirmation.setMaxInactiveInterval(600);
                    confirmation.setAttribute(PENDING_LINK,new PendingLink(provider,subject.toString(),email,(String)attrs.get("name"),target,System.currentTimeMillis()+600000));
                    res.sendRedirect(providers.publicUrl+"/?oauth_link=confirm&auth=signin#get-started");return;
                }
            }
            var response=auth.session(id);response.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE).forEach(c->res.addHeader(HttpHeaders.SET_COOKIE,c));
            if(session!=null)session.invalidate();
            res.sendRedirect(providers.publicUrl+"/?oauth_success="+(link==null?"signed_in":"linked"));
        }catch(Exception e){failure(req,res,e);}
    }
    void failure(HttpServletRequest req,HttpServletResponse res,Exception error)throws IOException{
        String code=error instanceof OAuth2AuthenticationException e?e.getError().getErrorCode():"failed";
        if(!Set.of("account_exists","identity_in_use","provider_already_linked","verified_email_required","link_expired","confirmation_account_mismatch").contains(code))code="failed";
        var session=req.getSession(false);String intent=session!=null&&"signup".equals(session.getAttribute("oauthIntent"))?"signup":"signin";
        if(session!=null)session.invalidate();
        res.sendRedirect(providers.publicUrl+"/?oauth_error="+code+"&auth="+intent+"#get-started");
    }
}
