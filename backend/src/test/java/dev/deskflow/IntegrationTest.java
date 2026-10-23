package dev.deskflow;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mail.javamail.JavaMailSender;
import org.testcontainers.containers.PostgreSQLContainer;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"app.role=test","app.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","spring.rabbitmq.listener.simple.auto-startup=false","management.health.rabbit.enabled=false","GOOGLE_CLIENT_ID=oauth-test-client","GOOGLE_CLIENT_SECRET=test-secret","GITHUB_CLIENT_ID=github-test-client","GITHUB_CLIENT_SECRET=test-secret"})
@AutoConfigureMockMvc
class IntegrationTest {
    static PostgreSQLContainer<?> postgres;
    @AfterAll static void stopContainer(){if(postgres!=null)postgres.stop();}
    @DynamicPropertySource static void database(DynamicPropertyRegistry props) {
        String external=System.getenv("TEST_DATABASE_URL");
        if(external!=null) {
            props.add("spring.datasource.url",()->external);
            props.add("spring.datasource.username",()->System.getenv("TEST_DATABASE_USER"));
            props.add("spring.datasource.password",()->System.getenv("TEST_DATABASE_PASSWORD"));
        } else {
            postgres=new PostgreSQLContainer<>("postgres:17-alpine");postgres.start();
            props.add("spring.datasource.url",postgres::getJdbcUrl);props.add("spring.datasource.username",postgres::getUsername);props.add("spring.datasource.password",postgres::getPassword);
        }
    }
    @Autowired Store store;
    @Autowired Engine engine;
    @Autowired MockMvc mvc;
    @Autowired SocialLogin social;
    @Autowired AccountRecovery recovery;
    org.springframework.test.web.servlet.ResultActions recoveryPost(String path,Map<String,Object> body)throws Exception{
        return mvc.perform(post("/api/auth/"+path).header("X-Deskflow-Request","1").contentType("application/json").content(Json.write(body)));
    }
    String emailedToken()throws Exception{
        recovery.executor.submit(()->{}).get(10,java.util.concurrent.TimeUnit.SECONDS);
        var capture=org.mockito.ArgumentCaptor.forClass(org.springframework.mail.SimpleMailMessage.class);
        verify(mail,timeout(3000)).send(capture.capture());
        String text=capture.getValue().getText();String token=text.substring(text.indexOf("#token=")+7).split("\\s")[0];
        assertTrue(token.matches("[A-Za-z0-9_-]{43}"));return token;
    }
    @Test void gmailSignInOpensExistingWorkspaceAndRetiresUnverifiedCredentials()throws Exception{
        setLinkPassword();var wf=workflow(simple());var old=session(owner);String email=owner+"@gmail.com",subject=UUID.randomUUID().toString();
        store.db.update("update users set email=? where id=?",email,owner);social.resolve("github",UUID.randomUUID().toString(),email,"Test",owner);
        var req=new org.springframework.mock.web.MockHttpServletRequest();var res=new org.springframework.mock.web.MockHttpServletResponse();
        social.success(req,res,socialPrincipal("google",subject,email,true));
        assertTrue(res.getRedirectedUrl().contains("oauth_success=signed_in"));assertNotNull(res.getHeader("Set-Cookie"));assertNull(req.getSession(false));
        assertEquals(owner,social.resolve("google",subject,email,"Test",null));
        assertNull(store.db.queryForObject("select password_hash from users where id=?",String.class,owner));
        assertNotNull(store.db.queryForObject("select email_verified_at from users where id=?",Object.class,owner));
        assertEquals(0,store.db.queryForObject("select count(*) from sessions where token_hash=?",Integer.class,Secrets.hash(old.getValue())));
        assertEquals(0,store.db.queryForObject("select count(*) from oauth_identities where user_id=? and provider='github'",Integer.class,owner));
        assertEquals(owner,store.db.queryForObject("select owner_id from workflows where id=?",UUID.class,wf.get("id")));
    }
    @Test void authenticatedGoogleConnectionPreservesTheOwnersPassword()throws Exception{
        setLinkPassword();String email=owner+"@gmail.com";store.db.update("update users set email=? where id=?",email,owner);var cookie=session(owner);
        var start=mvc.perform(get("/api/auth/oauth2/start/google").cookie(cookie)).andExpect(status().is3xxRedirection()).andReturn();
        var req=new org.springframework.mock.web.MockHttpServletRequest();req.setSession(start.getRequest().getSession(false));var res=new org.springframework.mock.web.MockHttpServletResponse();
        social.success(req,res,socialPrincipal("google",UUID.randomUUID().toString(),email,true));
        assertTrue(res.getRedirectedUrl().contains("oauth_success=signed_in"));
        assertTrue(social.auth.passwords.matches(LINK_PASSWORD,store.db.queryForObject("select password_hash from users where id=?",String.class,owner)));
    }
    @Test void googleWorkspaceClaimAllowsPasswordlessSignInButUnverifiedEmailDoesNot()throws Exception{
        setLinkPassword();store.db.update("update users set email_verified_at=now() where id=?",owner);
        var original=socialPrincipal("google",UUID.randomUUID().toString(),owner+"@example.com",true);
        var attributes=new HashMap<String,Object>(original.getPrincipal().getAttributes());attributes.put("hd","example.com");
        var principal=new org.springframework.security.oauth2.core.user.DefaultOAuth2User(original.getAuthorities(),attributes,"sub");
        var token=new org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken(principal,original.getAuthorities(),"google");
        var req=new org.springframework.mock.web.MockHttpServletRequest();var res=new org.springframework.mock.web.MockHttpServletResponse();social.success(req,res,token);
        assertTrue(res.getRedirectedUrl().contains("oauth_success=signed_in"));
        assertTrue(social.auth.passwords.matches(LINK_PASSWORD,store.db.queryForObject("select password_hash from users where id=?",String.class,owner)));
        assertFalse(social.authoritativeGoogle("google",Map.of("email","test@gmail.com","email_verified",false,"hd","example.com")));
        assertFalse(social.authoritativeGoogle("github",Map.of("email","test@gmail.com","email_verified",true)));
    }
    @Test void changedGoogleEmailDoesNotVerifyTheOriginalMailbox()throws Exception{
        String subject=UUID.randomUUID().toString();social.resolve("google",subject,owner+"@example.com","Test",owner);
        var req=new org.springframework.mock.web.MockHttpServletRequest();var res=new org.springframework.mock.web.MockHttpServletResponse();
        social.success(req,res,socialPrincipal("google",subject,UUID.randomUUID()+"@gmail.com",true));
        assertTrue(res.getRedirectedUrl().contains("oauth_success=signed_in"));
        assertNull(store.db.queryForObject("select email_verified_at from users where id=?",Object.class,owner));
    }
    @Test void externalGoogleEmailUsesEmailConfirmationRatherThanAPassword()throws Exception{
        setLinkPassword();String subject=UUID.randomUUID().toString();var pending=pendingSocial("google",subject);
        mvc.perform(post("/api/auth/oauth2/confirm-link").session(pending).header("X-Deskflow-Request","1").contentType("application/json").content(Json.write(Map.of("password",LINK_PASSWORD))))
            .andExpect(status().isConflict());
        mvc.perform(post("/api/auth/oauth2/email-link").session(pending).header("X-Deskflow-Request","1")).andExpect(status().isOk());
        String token=emailedToken();
        recoveryPost("oauth2/confirm-email",Map.of("token",token,"subject","forged")).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(owner.toString()));
        assertEquals(owner,social.resolve("google",subject,owner+"@example.com","Test",null));
        recoveryPost("oauth2/confirm-email",Map.of("token",token)).andExpect(status().isBadRequest());
    }
    @Test void forgotPasswordResponseDoesNotDiscloseAccountExistence()throws Exception{
        String known=recoveryPost("forgot-password",Map.of("email",owner+"@example.com")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsString();
        String token=emailedToken();
        assertEquals(1,store.db.queryForObject("select count(*) from account_email_tokens where token_hash=? and user_id=?",Integer.class,Secrets.hash(token),owner));
        assertEquals(0,store.db.queryForObject("select count(*) from account_email_tokens where token_hash=?",Integer.class,token));
        String unknown=recoveryPost("forgot-password",Map.of("email",UUID.randomUUID()+"@example.invalid")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        recovery.executor.submit(()->{}).get(10,java.util.concurrent.TimeUnit.SECONDS);assertEquals(known,unknown);verify(mail,times(1)).send(any(org.springframework.mail.SimpleMailMessage.class));
    }
    @Test void resetPasswordWorksOnceAndRevokesOldSessions()throws Exception{
        setLinkPassword();store.db.update("update users set email_verified_at=now() where id=?",owner);
        var old=session(owner);String subject=UUID.randomUUID().toString();social.resolve("google",subject,owner+"@example.com","Test",owner);
        recoveryPost("forgot-password",Map.of("email",owner+"@example.com")).andExpect(status().isOk());String token=emailedToken(),password="my-new-secure-password";
        recoveryPost("reset-password",Map.of("token",token,"password",password)).andExpect(status().isOk()).andExpect(header().doesNotExist("Set-Cookie"));
        mvc.perform(get("/api/auth/me").cookie(old)).andExpect(status().isUnauthorized());
        recoveryPost("login",Map.of("email",owner+"@example.com","password",LINK_PASSWORD)).andExpect(status().isUnauthorized());
        recoveryPost("login",Map.of("email",owner+"@example.com","password",password)).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(owner.toString()));
        recoveryPost("reset-password",Map.of("token",token,"password","another-new-password")).andExpect(status().isBadRequest());
        assertEquals(owner,social.resolve("google",subject,owner+"@example.com","Test",null));
    }
    @Test void shortPasswordsDoNotConsumeAResetLinkAndExpiredOrWrongPurposeLinksFail()throws Exception{
        recoveryPost("forgot-password",Map.of("email",owner+"@example.com")).andExpect(status().isOk());String token=emailedToken();
        recoveryPost("reset-password",Map.of("token",token,"password","short")).andExpect(status().isBadRequest());
        recoveryPost("oauth2/confirm-email",Map.of("token",token)).andExpect(status().isBadRequest());
        assertEquals(1,store.db.queryForObject("select count(*) from account_email_tokens where user_id=?",Integer.class,owner));
        store.db.update("update account_email_tokens set expires_at=now()-interval '1 minute' where user_id=?",owner);
        recoveryPost("reset-password",Map.of("token",token,"password","valid-new-password")).andExpect(status().isBadRequest());
        assertEquals("unused",store.db.queryForObject("select password_hash from users where id=?",String.class,owner));
    }
    @Test void resetCanSetAPasswordForASocialOnlyAccount()throws Exception{
        store.db.update("update users set password_hash=null,email_verified_at=now() where id=?",owner);
        recoveryPost("forgot-password",Map.of("email",owner+"@example.com")).andExpect(status().isOk());String token=emailedToken();
        recoveryPost("reset-password",Map.of("token",token,"password","social-account-password")).andExpect(status().isOk());
        recoveryPost("login",Map.of("email",owner+"@example.com","password","social-account-password")).andExpect(status().isOk());
    }
    @Test void forgotPasswordHasAMailCooldownAndSanitizesSmtpFailure()throws Exception{
        recoveryPost("forgot-password",Map.of("email",owner+"@example.com")).andExpect(status().isOk());emailedToken();
        recoveryPost("forgot-password",Map.of("email",owner+"@example.com")).andExpect(status().isOk());
        recovery.executor.submit(()->{}).get(10,java.util.concurrent.TimeUnit.SECONDS);verify(mail,times(1)).send(any(org.springframework.mail.SimpleMailMessage.class));
        store.db.update("delete from account_email_tokens where user_id=?",owner);
        doThrow(new org.springframework.mail.MailSendException("fake SMTP failure")).when(mail).send(any(org.springframework.mail.SimpleMailMessage.class));
        recoveryPost("forgot-password",Map.of("email",owner+"@example.com")).andExpect(status().isOk()).andExpect(content().json(Json.write(AccountRecovery.SENT)));
        recovery.executor.submit(()->{}).get(10,java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(0,store.db.queryForObject("select count(*) from account_email_tokens where user_id=?",Integer.class,owner));
    }
    @Test void recoveryRequiresSameOriginMutationHeaderAndIsRateLimited()throws Exception{
        mvc.perform(post("/api/auth/forgot-password").contentType("application/json").content(Json.write(Map.of("email",owner+"@example.com")))).andExpect(status().isForbidden());
        recoveryPost("reset-password",Map.of("token","invalid","password","valid-new-password")).andExpect(status().isBadRequest());
        social.auth.limits.put("127.0.0.1",new long[]{System.currentTimeMillis(),20});
        recoveryPost("forgot-password",Map.of("email",owner+"@example.com")).andExpect(status().isTooManyRequests());
    }
    static final String LINK_PASSWORD="existing-workspace-password";
    org.springframework.mock.web.MockHttpSession pendingSocial(String provider,String subject)throws Exception{
        var req=new org.springframework.mock.web.MockHttpServletRequest();var res=new org.springframework.mock.web.MockHttpServletResponse();
        social.success(req,res,socialPrincipal(provider,subject,owner+"@example.com",true));
        assertTrue(res.getRedirectedUrl().contains("oauth_link=confirm"));assertNull(res.getHeader("Set-Cookie"));
        return (org.springframework.mock.web.MockHttpSession)req.getSession(false);
    }
    void setLinkPassword(){store.db.update("update users set password_hash=? where id=?",social.auth.passwords.encode(LINK_PASSWORD),owner);}
    @Test void existingEmailCallbackOffersConfirmationInsteadOfFailure()throws Exception{
        setLinkPassword();var pending=pendingSocial("google",UUID.randomUUID().toString());
        mvc.perform(get("/api/auth/oauth2/pending-link").session(pending)).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.active").value(true))
            .andExpect(jsonPath("$.email").value(owner+"@example.com")).andExpect(jsonPath("$.hasPassword").value(true));
        assertEquals(0,store.db.queryForObject("select count(*) from oauth_identities where user_id=?",Integer.class,owner));
    }
    @Test void passwordConfirmationLinksAndSignsIntoTheOriginalWorkspace()throws Exception{
        setLinkPassword();var wf=workflow(simple());String subject=UUID.randomUUID().toString();var pending=pendingSocial("github",subject);
        mvc.perform(post("/api/auth/oauth2/confirm-link").session(pending).header("X-Deskflow-Request","1").contentType("application/json")
            .content(Json.write(Map.of("password",LINK_PASSWORD,"email","forged@example.com","provider","google","subject","forged"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(owner.toString())).andExpect(header().string("Set-Cookie",org.hamcrest.Matchers.containsString("deskflow_session=")));
        assertTrue(pending.isInvalid());assertEquals(owner,social.resolve("github",subject,"changed@example.com","Changed",null));
        assertEquals(owner,store.db.queryForObject("select owner_id from workflows where id=?",UUID.class,wf.get("id")));
        var req=new org.springframework.mock.web.MockHttpServletRequest();var res=new org.springframework.mock.web.MockHttpServletResponse();
        social.success(req,res,socialPrincipal("github",subject,owner+"@example.com",true));
        assertTrue(res.getRedirectedUrl().contains("oauth_success=signed_in"));assertNull(req.getSession(false));
    }
    @Test void wrongPasswordCannotLinkAndKeepsConfirmationRetryable()throws Exception{
        setLinkPassword();var pending=pendingSocial("github",UUID.randomUUID().toString());
        mvc.perform(post("/api/auth/oauth2/confirm-link").session(pending).header("X-Deskflow-Request","1").contentType("application/json").content("{\"password\":\"incorrect-password\"}"))
            .andExpect(status().isUnauthorized());
        assertFalse(pending.isInvalid());assertEquals(0,store.db.queryForObject("select count(*) from oauth_identities where user_id=?",Integer.class,owner));
    }
    @Test void signedInOwnerCanExplicitlyConfirmWithoutAnotherPassword()throws Exception{
        String subject=UUID.randomUUID().toString();var pending=pendingSocial("google",subject);var cookie=session(owner);
        mvc.perform(get("/api/auth/oauth2/pending-link").session(pending).cookie(cookie)).andExpect(status().isOk()).andExpect(jsonPath("$.alreadySignedIn").value(true));
        mvc.perform(post("/api/auth/oauth2/confirm-link").session(pending).cookie(cookie).header("X-Deskflow-Request","1").contentType("application/json").content("{}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(owner.toString()));
        assertEquals(owner,social.resolve("google",subject,owner+"@example.com","Test",null));
    }
    @Test void signInFailureReturnsToSignInRatherThanRegistration()throws Exception{
        var started=mvc.perform(get("/api/auth/oauth2/start/google").param("intent","login")).andExpect(status().is3xxRedirection()).andReturn();
        var req=new org.springframework.mock.web.MockHttpServletRequest();req.setSession(started.getRequest().getSession(false));var res=new org.springframework.mock.web.MockHttpServletResponse();
        social.failure(req,res,new org.springframework.security.oauth2.core.OAuth2AuthenticationException("access_denied"));
        assertTrue(res.getRedirectedUrl().contains("auth=signin"));
    }
    @Test void competingLinkCannotReplaceAnExistingProvider()throws Exception{
        setLinkPassword();var pending=pendingSocial("github",UUID.randomUUID().toString());
        String original=UUID.randomUUID().toString();social.resolve("github",original,owner+"@example.com","Test",owner);
        mvc.perform(post("/api/auth/oauth2/confirm-link").session(pending).header("X-Deskflow-Request","1").contentType("application/json").content(Json.write(Map.of("password",LINK_PASSWORD))))
            .andExpect(status().isConflict());
        assertEquals(original,store.db.queryForObject("select subject from oauth_identities where user_id=? and provider='github'",String.class,owner));
    }
    @Test void pendingProofCannotBeUsedFromAnotherBrowserOrWithoutMutationHeader()throws Exception{
        setLinkPassword();var pending=pendingSocial("google",UUID.randomUUID().toString());
        mvc.perform(get("/api/auth/oauth2/pending-link")).andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        mvc.perform(post("/api/auth/oauth2/confirm-link").session(pending).contentType("application/json").content(Json.write(Map.of("password",LINK_PASSWORD)))).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/oauth2/confirm-link").header("X-Deskflow-Request","1").contentType("application/json").content(Json.write(Map.of("password",LINK_PASSWORD)))).andExpect(status().isGone());
    }
    @Test void expiredAndCancelledProofsCannotLinkAccounts()throws Exception{
        var pending=pendingSocial("google",UUID.randomUUID().toString());var proof=(SocialLogin.PendingLink)pending.getAttribute(SocialLogin.PENDING_LINK);
        pending.setAttribute(SocialLogin.PENDING_LINK,new SocialLogin.PendingLink(proof.provider(),proof.subject(),proof.email(),proof.name(),proof.userId(),0));
        mvc.perform(post("/api/auth/oauth2/confirm-link").session(pending).header("X-Deskflow-Request","1").contentType("application/json").content("{}")).andExpect(status().isGone());
        var cancel=pendingSocial("google",UUID.randomUUID().toString());
        mvc.perform(post("/api/auth/oauth2/cancel-link").session(cancel).header("X-Deskflow-Request","1")).andExpect(status().isOk());assertTrue(cancel.isInvalid());
        assertEquals(0,store.db.queryForObject("select count(*) from oauth_identities where user_id=?",Integer.class,owner));
    }
    @Test void existingSocialProviderCanConfirmANewProviderWithoutPassword()throws Exception{
        store.db.update("update users set password_hash=null where id=?",owner);
        String googleSubject=UUID.randomUUID().toString(),githubSubject=UUID.randomUUID().toString();
        social.resolve("google",googleSubject,owner+"@example.com","Test",owner);
        var pending=pendingSocial("github",githubSubject);
        mvc.perform(get("/api/auth/oauth2/pending-link").session(pending)).andExpect(status().isOk()).andExpect(jsonPath("$.hasPassword").value(false)).andExpect(jsonPath("$.existingProviders[0]").value("google"));
        var started=mvc.perform(get("/api/auth/oauth2/start/google").param("confirm","true").session(pending)).andExpect(status().is3xxRedirection()).andReturn();
        var req=new org.springframework.mock.web.MockHttpServletRequest();req.setSession(started.getRequest().getSession(false));
        var res=new org.springframework.mock.web.MockHttpServletResponse();social.success(req,res,socialPrincipal("google",googleSubject,owner+"@example.com",true));
        assertTrue(res.getRedirectedUrl().contains("oauth_success=linked"));assertEquals(owner,social.resolve("github",githubSubject,owner+"@example.com","Test",null));
    }
    @Test void confirmingWithADifferentSocialAccountCannotMergeWorkspaces()throws Exception{
        String googleSubject=UUID.randomUUID().toString();social.resolve("google",googleSubject,owner+"@example.com","Test",owner);
        var pending=pendingSocial("github",UUID.randomUUID().toString());
        var started=mvc.perform(get("/api/auth/oauth2/start/google").param("confirm","true").session(pending)).andReturn();
        var req=new org.springframework.mock.web.MockHttpServletRequest();req.setSession(started.getRequest().getSession(false));
        var res=new org.springframework.mock.web.MockHttpServletResponse();social.success(req,res,socialPrincipal("google",UUID.randomUUID().toString(),"unrelated@example.com",true));
        assertTrue(res.getRedirectedUrl().contains("oauth_error=confirmation_account_mismatch"));assertNull(res.getHeader("Set-Cookie"));
        assertEquals(1,store.db.queryForObject("select count(*) from oauth_identities where user_id=?",Integer.class,owner));
    }
    @Test void callbackWithoutValidStateCannotCreateAnAccount()throws Exception{
        int before=store.db.queryForObject("select count(*) from users",Integer.class);
        mvc.perform(get("/api/auth/oauth2/callback/google").param("code","not-a-real-code").param("state","not-the-browser-state"))
            .andExpect(status().is3xxRedirection()).andExpect(header().string("Location",org.hamcrest.Matchers.containsString("oauth_error=failed")));
        assertEquals(before,store.db.queryForObject("select count(*) from users",Integer.class));
    }
    @Test void callbackRejectsUnverifiedEmailWithoutSession()throws Exception{
        var req=new org.springframework.mock.web.MockHttpServletRequest();var res=new org.springframework.mock.web.MockHttpServletResponse();
        social.success(req,res,socialPrincipal(false));
        assertTrue(res.getRedirectedUrl().contains("oauth_error=verified_email_required"));assertNull(res.getHeader("Set-Cookie"));
    }
    @Test void callbackCannotLinkAfterOriginalSessionExpires()throws Exception{
        var req=new org.springframework.mock.web.MockHttpServletRequest();var res=new org.springframework.mock.web.MockHttpServletResponse();
        req.getSession().setAttribute("linkUser",owner);req.getSession().setAttribute("linkSession","expired-session");
        social.success(req,res,socialPrincipal(true));
        assertTrue(res.getRedirectedUrl().contains("oauth_error=link_expired"));assertNull(res.getHeader("Set-Cookie"));
    }
    @Test void verifiedCallbackCreatesOnlyAnOpaqueDeskflowSession()throws Exception{
        var req=new org.springframework.mock.web.MockHttpServletRequest();var res=new org.springframework.mock.web.MockHttpServletResponse();
        social.success(req,res,socialPrincipal(true));
        assertTrue(res.getRedirectedUrl().contains("oauth_success=signed_in"));
        assertTrue(res.getHeader("Set-Cookie").contains("deskflow_session="));assertTrue(res.getHeader("Set-Cookie").contains("HttpOnly"));
    }
    org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken socialPrincipal(boolean verified){
        return socialPrincipal("google",UUID.randomUUID().toString(),UUID.randomUUID()+"@example.com",verified);
    }
    org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken socialPrincipal(String provider,String subject,String email,boolean verified){
        var authorities=List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER"));
        String attribute=provider.equals("google")?"sub":"id";
        var principal=new org.springframework.security.oauth2.core.user.DefaultOAuth2User(authorities,Map.of(attribute,subject,"email",email,"email_verified",verified,"name","OAuth test"),attribute);
        return new org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken(principal,authorities,provider);
    }
    @Test void socialLoginUsesProviderSubjectEvenAfterEmailChanges(){
        String subject=UUID.randomUUID().toString();String email=UUID.randomUUID()+"@example.com";
        UUID id=social.resolve("google",subject,email,"Social User",null);
        assertEquals(id,social.resolve("google",subject,UUID.randomUUID()+"@example.com","New name",null));
        assertEquals(1,store.db.queryForObject("select count(*) from oauth_identities where user_id=?",Integer.class,id));
    }
    @Test void socialLoginNeverAutomaticallyMergesAnExistingEmail(){
        assertThrows(org.springframework.security.oauth2.core.OAuth2AuthenticationException.class,()->social.resolve("google",UUID.randomUUID().toString(),owner+"@example.com","Test",null));
        assertEquals(0,store.db.queryForObject("select count(*) from oauth_identities where user_id=?",Integer.class,owner));
    }
    @Test void explicitLinkKeepsWorkspaceAndRejectsAnotherOwner(){
        String subject=UUID.randomUUID().toString();String email=UUID.randomUUID()+"@example.com";
        assertEquals(owner,social.resolve("github",subject,email,"Test",owner));
        assertEquals(owner,social.resolve("github",subject,email,"Test",null));
        assertThrows(org.springframework.security.oauth2.core.OAuth2AuthenticationException.class,()->social.resolve("github",subject,email,"Test",UUID.randomUUID()));
    }
    @Test void socialOnlyAccountsCannotUsePasswordLogin()throws Exception{
        String email=UUID.randomUUID()+"@example.com";
        social.resolve("google",UUID.randomUUID().toString(),email,"Test",null);
        mvc.perform(post("/api/auth/login").header("X-Deskflow-Request","1").contentType("application/json").content(Json.write(Map.of("email",email,"password","incorrect-password")))).andExpect(status().isUnauthorized());
    }
    @Test void socialReadinessIsPublicButConnectionsArePrivate()throws Exception{
        mvc.perform(get("/api/auth/oauth2/providers")).andExpect(status().isOk()).andExpect(jsonPath("$.google").isBoolean()).andExpect(jsonPath("$.github").isBoolean());
        mvc.perform(get("/api/auth/oauth2/connections")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/oauth2/connections").cookie(session(owner))).andExpect(status().isOk()).andExpect(content().json("[]"));
    }
    @MockitoSpyBean Actions actions;
    @MockitoBean JavaMailSender mail;
    UUID owner;
    @BeforeEach void owner(){social.auth.limits.clear();owner=UUID.randomUUID();store.db.update("insert into users(id,email,name,password_hash) values(?,?,?,?)",owner,owner+"@example.com","Test User","unused");}
    Cookie session(UUID user){String token=Secrets.token();store.db.update("insert into sessions(token_hash,user_id,expires_at) values(?,?,now()+interval '1 day')",Secrets.hash(token),user);return new Cookie("deskflow_session",token);}
    JsonNode simple(){return Json.read("{\"nodes\":["+GraphTest.node("start","manual","{}")+","+GraphTest.node("map","transform","{\"output\":{\"total\":\"{{input.total}}\"}}")+"],\"edges\":["+GraphTest.edge("start","map")+"]}");}
    Map<String,Object> workflow(JsonNode definition){return store.save(owner,null,Json.M.valueToTree(Json.map("name","Test workflow","description","Fixture","active",false,"definition",definition)));}
    UUID enqueue(Map<String,Object> wf){return store.enqueue(wf,Json.read("{\"total\":150}"),"manual",null);}
    Map<String,Object> step(UUID id,String node){return store.rows("select * from steps where execution_id=? and node_id=?",id,node).getFirst();}
    String runStatus(UUID id){return store.db.queryForObject("select status from executions where id=?",String.class,id);}
    void finish(UUID id){for(int i=0;i<15 && Set.of("QUEUED","RUNNING","RETRYING").contains(runStatus(id));i++){store.db.update("update executions set next_attempt_at=now() where id=?",id);engine.advance(id);}}
    @Test void runsAndPersistsTypedOutput(){UUID id=enqueue(workflow(simple()));finish(id);assertEquals("SUCCEEDED",runStatus(id));assertEquals(150,((JsonNode)step(id,"map").get("output")).path("total").asInt());assertTrue(store.db.queryForObject("select count(*) from outbox where execution_id=?",Integer.class,id)>0);}
    @Test void deduplicatesAndRejectsConflictingPayload(){var wf=workflow(simple());var input=Json.read("{\"total\":150}");UUID a=store.enqueue(wf,input,"manual","same-event");assertEquals(a,store.enqueue(wf,input,"manual","same-event"));assertThrows(org.springframework.web.server.ResponseStatusException.class,()->store.enqueue(wf,Json.read("{\"total\":151}"),"manual","same-event"));}
    @Test void isolatesTenantsAndRequiresMutationHeader() throws Exception {
        var wf=workflow(simple());UUID other=UUID.randomUUID();store.db.update("insert into users(id,email,name,password_hash) values(?,?,?,?)",other,other+"@example.com","Other","unused");
        mvc.perform(get("/api/workflows/"+wf.get("id")).cookie(session(other))).andExpect(status().isNotFound());
        mvc.perform(get("/api/workflows/"+wf.get("id"))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/workflows").cookie(session(owner)).contentType("application/json").content("{}")).andExpect(status().isForbidden());
    }
    @Test void snapshotsVersionBeforeEdits(){var wf=workflow(simple());UUID id=enqueue(wf);var updated=simple().deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)updated.path("nodes").get(1).path("data").path("config")).set("output",Json.read("{\"changed\":true}"));store.save(owner,(UUID)wf.get("id"),Json.M.valueToTree(Json.map("name","Changed","version",1,"definition",updated)));finish(id);assertEquals(150,((JsonNode)step(id,"map").get("output")).path("total").asInt());assertEquals(1,store.db.queryForObject("select version from executions where id=?",Integer.class,id));}
    @Test void branchSkipsUnselectedActionAndJoinsOnce(){
        String nodes=GraphTest.node("start","manual","{}")+","+GraphTest.node("check","condition","{\"field\":\"input.total\",\"operator\":\"greaterThan\",\"value\":100}")+","+GraphTest.node("yes","transform","{\"output\":{\"selected\":true}}")+","+GraphTest.node("no","http","{\"method\":\"GET\",\"url\":\"http://127.0.0.1\"}")+","+GraphTest.node("join","transform","{\"output\":{\"joined\":true}}");
        String edges=GraphTest.edge("start","check")+",{\"source\":\"check\",\"target\":\"yes\",\"sourceHandle\":\"true\"},{\"source\":\"check\",\"target\":\"no\",\"sourceHandle\":\"false\"},"+GraphTest.edge("yes","join")+","+GraphTest.edge("no","join");
        UUID id=enqueue(workflow(Json.read("{\"nodes\":["+nodes+"],\"edges\":["+edges+"]}")));finish(id);assertEquals("SUCCEEDED",runStatus(id));assertEquals("SKIPPED",step(id,"no").get("status"));assertEquals(1,step(id,"join").get("attempts"));
    }
    @Test void retriesTransientErrorsThenSucceeds(){var wf=workflow(simple());UUID id=enqueue(wf);engine.advance(id);doThrow(new Actions.Failure("Temporary failure",true)).doCallRealMethod().when(actions).execute(argThat(n->n.id().equals("map")),any(),eq(owner),eq(id));engine.advance(id);assertEquals("RETRYING",runStatus(id));finish(id);assertEquals("SUCCEEDED",runStatus(id));assertEquals(2,step(id,"map").get("attempts"));}
    @Test void stopsAfterThreeAttempts(){UUID id=enqueue(workflow(simple()));engine.advance(id);doThrow(new Actions.Failure("Temporary failure",true)).when(actions).execute(argThat(n->n.id().equals("map")),any(),eq(owner),eq(id));finish(id);assertEquals("FAILED",runStatus(id));assertEquals(3,step(id,"map").get("attempts"));}
    @Test void permanentErrorsDoNotRetry(){UUID id=enqueue(workflow(simple()));engine.advance(id);doThrow(new Actions.Failure("Bad configuration",false)).when(actions).execute(argThat(n->n.id().equals("map")),any(),eq(owner),eq(id));finish(id);assertEquals("FAILED",runStatus(id));assertEquals(1,step(id,"map").get("attempts"));}
    @Test void cancellationSkipsUnstartedSteps(){UUID id=enqueue(workflow(simple()));engine.advance(id);store.db.update("update executions set cancel_requested=true where id=?",id);engine.advance(id);assertEquals("CANCELLED",runStatus(id));assertEquals("SKIPPED",step(id,"map").get("status"));}
    @Test void recoversExpiredLeaseWithoutRepeatingCompletedStep(){UUID id=enqueue(workflow(simple()));engine.advance(id);store.db.update("update executions set lease_token=?,lease_until=now()-interval '1 second' where id=?",UUID.randomUUID(),id);store.db.update("update steps set status='RUNNING',attempts=1 where execution_id=? and node_id='map'",id);finish(id);assertEquals("SUCCEEDED",runStatus(id));assertEquals(1,step(id,"start").get("attempts"));assertEquals(2,step(id,"map").get("attempts"));}
    @Test void unexpiredLeasePreventsDuplicateExecution(){UUID id=enqueue(workflow(simple()));store.db.update("update executions set lease_token=?,lease_until=now()+interval '1 minute' where id=?",UUID.randomUUID(),id);engine.advance(id);assertEquals(0,step(id,"start").get("attempts"));}
    @Test void webhookRequiresActivationAndCorrectSecret() throws Exception {
        var def=simple();((com.fasterxml.jackson.databind.node.ObjectNode)def.path("nodes").get(0).path("data")).put("kind","webhook");var wf=workflow(def);String url="/hooks/"+wf.get("id")+"/"+wf.get("webhook_token");
        mvc.perform(post(url).contentType("application/json").content("{\"total\":2}")).andExpect(status().isNotFound());
        store.db.update("update workflows set active=true where id=?",wf.get("id"));
        mvc.perform(post(url).header("Idempotency-Key","webhook-event").contentType("application/json").content("{\"total\":2}")).andExpect(status().isAccepted());
        mvc.perform(post(url).header("Idempotency-Key","webhook-event").contentType("application/json").content("{\"total\":2}")).andExpect(status().isAccepted());
        assertEquals(1,store.db.queryForObject("select count(*) from executions where workflow_id=?",Integer.class,wf.get("id")));
    }
    @Test void credentialApisNeverReturnPlaintext() throws Exception {
        var cookie=session(owner);String secret="super-private-token-123";
        mvc.perform(post("/api/credentials").cookie(cookie).header("X-Deskflow-Request","1").contentType("application/json").content(Json.write(Map.of("name","Test token","value",secret)))).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(secret))));
        mvc.perform(get("/api/credentials").cookie(cookie)).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(secret))));
        String encrypted=store.db.queryForObject("select encrypted_value from credentials where owner_id=?",String.class,owner);assertFalse(encrypted.contains(secret));assertEquals("[REDACTED]",store.redact(owner,secret));
    }
    @Test void rejectsStaleWorkflowSaves(){var wf=workflow(simple());assertThrows(org.springframework.web.server.ResponseStatusException.class,()->store.save(owner,(UUID)wf.get("id"),Json.M.valueToTree(Json.map("name","Stale","version",0,"definition",simple()))));}
    @Test void schedulerEnqueuesDueWorkflowOnlyOnce(){
        var def=simple();var trigger=(com.fasterxml.jackson.databind.node.ObjectNode)def.path("nodes").get(0).path("data");trigger.put("kind","schedule");trigger.set("config",Json.read("{\"intervalMinutes\":1,\"input\":{\"total\":25}}"));
        var wf=workflow(def);store.db.update("update workflows set active=true,next_run_at=now()-interval '1 minute' where id=?",wf.get("id"));
        var scheduler=new Dispatch(store,null);scheduler.schedules();scheduler.schedules();
        assertEquals(1,store.db.queryForObject("select count(*) from executions where workflow_id=?",Integer.class,wf.get("id")));
        assertEquals("schedule",store.db.queryForObject("select trigger_type from executions where workflow_id=?",String.class,wf.get("id")));
    }
    JsonNode chain(String... pairs){
        var nodes=Json.M.createArrayNode();var edges=Json.M.createArrayNode();nodes.add(Json.read(GraphTest.node("start","manual","{}")));
        String previous="start";for(int i=0;i<pairs.length;i+=2){String id="n"+(i/2);nodes.add(Json.read(GraphTest.node(id,pairs[i],pairs[i+1])));edges.add(Json.read(GraphTest.edge(previous,id)));previous=id;}
        return Json.M.valueToTree(Json.map("nodes",nodes,"edges",edges));
    }
    void pump(UUID id){store.db.update("update executions set next_attempt_at=now() where id=?",id);engine.advance(id);}
    void settle(UUID id){for(int i=0;i<40&&Set.of("QUEUED","RUNNING","RETRYING","WAITING").contains(runStatus(id));i++)pump(id);}
    JsonNode output(UUID id,String node){return (JsonNode)step(id,node).get("output");}
    @Test void filterStopsExternalActions(){
        UUID id=enqueue(workflow(chain("filter","{\"field\":\"input.total\",\"operator\":\"lessThan\",\"value\":100}","email","{\"to\":\"test@example.com\",\"subject\":\"Skipped\"}")));
        settle(id);assertEquals("SUCCEEDED",runStatus(id));assertEquals("SKIPPED",step(id,"n1").get("status"));verifyNoInteractions(mail);
    }
    @Test void switchAndMergeKeepOnlySelectedParents(){
        var def=chain("switch","{\"field\":\"input.total\",\"cases\":[{\"name\":\"large\",\"value\":150}]}","transform","{\"output\":{\"selected\":true}}","merge","{}");
        ((com.fasterxml.jackson.databind.node.ObjectNode)def.path("edges").get(1)).put("sourceHandle","large");
        UUID id=enqueue(workflow(def));settle(id);assertTrue(output(id,"n2").path("parents").path("n1").path("selected").asBoolean());
    }
    @Test void delayPersistsDeadlineAndDoesNotSpendRetries(){
        UUID id=enqueue(workflow(chain("delay","{\"seconds\":60}","transform","{\"output\":{\"done\":true}}")));
        pump(id);pump(id);assertEquals("WAITING",runStatus(id));assertTrue(((JsonNode)step(id,"n0").get("state")).has("resumeAt"));
        pump(id);assertEquals(1,step(id,"n0").get("attempts"));
        store.db.update("update steps set state=jsonb_build_object('resumeAt',?::text) where execution_id=? and node_id='n0'",java.time.Instant.now().minusSeconds(1).toString(),id);
        settle(id);assertEquals("SUCCEEDED",runStatus(id));assertEquals(1,step(id,"n0").get("attempts"));
    }
    @Test void approvalDecisionIsOwnedDurableAndSingleUse() throws Exception {
        var def=chain("approval","{\"title\":\"Approve order {{input.total}}?\"}","transform","{\"output\":{\"rejected\":true}}");
        ((com.fasterxml.jackson.databind.node.ObjectNode)def.path("edges").get(1)).put("sourceHandle","false");
        UUID id=enqueue(workflow(def));pump(id);pump(id);assertEquals("WAITING",runStatus(id));
        UUID approval=store.db.queryForObject("select id from approvals where execution_id=?",UUID.class,id);
        UUID other=UUID.randomUUID();store.db.update("insert into users(id,email,name,password_hash) values(?,?,?,?)",other,other+"@example.com","Other","unused");
        mvc.perform(post("/api/approvals/"+approval+"/decision").cookie(session(other)).header("X-Deskflow-Request","1").contentType("application/json").content("{\"approved\":false}")).andExpect(status().isNotFound());
        var cookie=session(owner);
        mvc.perform(post("/api/approvals/"+approval+"/decision").cookie(cookie).header("X-Deskflow-Request","1").contentType("application/json").content("{\"approved\":false}")).andExpect(status().isOk());
        mvc.perform(post("/api/approvals/"+approval+"/decision").cookie(cookie).header("X-Deskflow-Request","1").contentType("application/json").content("{\"approved\":true}")).andExpect(status().isConflict());
        settle(id);assertTrue(output(id,"n1").path("rejected").asBoolean());assertEquals(1,step(id,"n0").get("attempts"));
    }
    @Test void loopUsesDurableChildrenWithSharedBudget(){
        var child=workflow(chain("email","{\"to\":\"test@example.com\",\"subject\":\"Item {{input.item}}\"}"));
        var def=chain("loop",Json.write(Json.map("workflowId",child.get("id"),"items",List.of("A","B"))));
        ((com.fasterxml.jackson.databind.node.ObjectNode)def).set("settings",Json.read("{\"effectLimit\":1}"));
        UUID id=enqueue(workflow(def));pump(id);pump(id);
        var children=store.db.queryForList("select id from executions where parent_id=? order by child_index",UUID.class,id);assertEquals(2,children.size());
        children.forEach(this::settle);settle(id);assertEquals("FAILED",runStatus(id));
        assertEquals(1,store.db.queryForObject("select effect_count from executions where id=?",Integer.class,id));
        assertEquals(1,store.execution(owner,children.getFirst()).get("effect_limit"));verify(mail,times(1)).send(any(org.springframework.mail.SimpleMailMessage.class));
        assertEquals(2,store.db.queryForObject("select count(*) from executions where parent_id=?",Integer.class,id));
    }
    @Test void childSnapshotSurvivesChildEdits(){
        var child=workflow(simple());var def=chain("subworkflow",Json.write(Json.map("workflowId",child.get("id"),"input",Json.read("{\"total\":7}"))));
        UUID id=enqueue(workflow(def));pump(id);pump(id);UUID childId=store.db.queryForObject("select id from executions where parent_id=?",UUID.class,id);
        store.save(owner,(UUID)child.get("id"),Json.M.valueToTree(Json.map("name","Changed child","version",1,"definition",chain("transform","{\"output\":{\"changed\":true}}"))));
        settle(childId);settle(id);assertEquals("SUCCEEDED",runStatus(id));assertEquals(7,output(id,"n0").path("items").path(0).path("steps").path("map").path("total").asInt());
    }
    @Test void errorRouteRecoversWithoutSendingNormalAction(){
        var def=chain("fail","{\"message\":\"Expected failure\",\"onError\":\"route\"}","transform","{\"output\":{\"handled\":\"{{steps.n0.error.message}}\"}}");
        ((com.fasterxml.jackson.databind.node.ObjectNode)def.path("edges").get(1)).put("sourceHandle","error");
        UUID id=enqueue(workflow(def));settle(id);assertEquals("SUCCEEDED",runStatus(id));assertEquals("RECOVERED",step(id,"n0").get("status"));assertEquals("Expected failure",output(id,"n1").path("handled").asText());
    }
    @Test void resumeRetainsCompletedActions() throws Exception {
        UUID id=enqueue(workflow(chain("email","{\"to\":\"test@example.com\",\"subject\":\"Once\"}","transform","{\"output\":{\"done\":true}}")));
        doThrow(new Actions.Failure("Fixable failure",false)).doCallRealMethod().when(actions).execute(argThat(n->n.id().equals("n1")),any(),eq(owner),eq(id));
        settle(id);assertEquals("FAILED",runStatus(id));
        mvc.perform(post("/api/executions/"+id+"/resume").cookie(session(owner)).header("X-Deskflow-Request","1")).andExpect(status().isOk());settle(id);
        assertEquals("SUCCEEDED",runStatus(id));assertEquals(1,step(id,"n0").get("attempts"));verify(mail,times(1)).send(any(org.springframework.mail.SimpleMailMessage.class));
    }
    @Test void tablesEnforceOwnershipAndWriteBudget() throws Exception {
        UUID table=UUID.randomUUID();store.db.update("insert into data_tables(id,owner_id,name) values(?,?,?)",table,owner,"Orders");
        UUID id=enqueue(workflow(chain("table",Json.write(Json.map("tableId",table,"operation","upsert","key","order-1","value",Map.of("total","{{input.total}}"))),"table",Json.write(Json.map("tableId",table,"operation","get","key","order-1")))));
        settle(id);assertEquals(150,output(id,"n1").path("record").path("value").path("total").asInt());assertEquals(1,store.db.queryForObject("select effect_count from executions where id=?",Integer.class,id));
        UUID other=UUID.randomUUID();store.db.update("insert into users(id,email,name,password_hash) values(?,?,?,?)",other,other+"@example.com","Other","unused");
        mvc.perform(get("/api/tables/"+table+"/records").cookie(session(other))).andExpect(status().isNotFound());
        assertThrows(IllegalArgumentException.class,()->store.save(other,null,Json.M.valueToTree(Json.map("name","Foreign table","definition",chain("table",Json.write(Json.map("tableId",table,"operation","get","key","order-1")))))));
    }
    @Autowired ImpactLab lab;
    @Autowired EffectGuard guard;
    @Test void impactLabPreviewsEffectsWithoutDispatchOrWrites(){
        UUID table=UUID.randomUUID();store.db.update("insert into data_tables(id,owner_id,name) values(?,?,?)",table,owner,"Preview table");
        var def=chain("email","{\"to\":\"test@example.com\",\"subject\":\"Preview\"}","table",Json.write(Json.map("tableId",table,"operation","upsert","key","never-created","value",Map.of("total",1))),"http","{\"url\":\"https://example.com\",\"method\":\"GET\",\"pinnedOutput\":{\"status\":200,\"body\":{\"ok\":true}}}");
        ((com.fasterxml.jackson.databind.node.ObjectNode)def).set("settings",Json.read("{\"effectLimit\":2}"));
        int executions=store.db.queryForObject("select count(*) from executions where owner_id=?",Integer.class,owner);
        var result=lab.simulate(owner,def,Json.read("{}"),Json.read("{}"));assertEquals("BUDGET_EXCEEDED",result.get("status"));assertEquals(3,result.get("effectCount"));assertEquals(0,result.get("sideEffectsDispatched"));
        assertEquals(0,store.db.queryForObject("select count(*) from data_records where table_id=?",Integer.class,table));assertEquals(executions,store.db.queryForObject("select count(*) from executions where owner_id=?",Integer.class,owner));verifyNoInteractions(mail);verifyNoInteractions(actions);
    }
    @Test void impactLabComparesSameInputAcrossVersions(){
        var before=chain("transform","{\"output\":{\"fee\":1}}");var after=chain("transform","{\"output\":{\"fee\":2}}");
        var result=Json.M.valueToTree(lab.compare(owner,before,after,Json.read("{}"),Json.read("{}")));assertEquals("n0",result.path("changes").path(0).path("node").asText());assertEquals(1,result.path("changes").size());assertEquals(0,result.path("sideEffectsDispatched").asInt());
    }
    @Test void concurrentReservationsCannotExceedRootBudget() throws Exception {
        var def=simple().deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)def).set("settings",Json.read("{\"effectLimit\":1}"));UUID id=enqueue(workflow(def));
        try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){
            var jobs=new ArrayList<java.util.concurrent.Future<Boolean>>();for(int i=0;i<6;i++)jobs.add(pool.submit(()->{try{guard.reserve(id,new Graph.Node("test","http","test",Json.read("{}")),"https://example.com");return true;}catch(Actions.Failure expected){return false;}}));
            int allowed=0;for(var job:jobs)if(job.get())allowed++;assertEquals(1,allowed);
        }
        assertEquals(1,store.db.queryForObject("select count(*) from effect_events where root_id=?",Integer.class,id));
    }
    @Test void allowedHostsPolicyBlocksBeforeDispatch(){
        var def=simple().deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)def).set("settings",Json.read("{\"allowedHosts\":[\"api.example.com\"]}"));UUID id=enqueue(workflow(def));
        assertDoesNotThrow(()->guard.destination(id,"API.EXAMPLE.COM"));assertThrows(Actions.Failure.class,()->guard.destination(id,"other.example.com"));assertEquals(0,store.db.queryForObject("select effect_count from executions where id=?",Integer.class,id));
    }
    @Test void publicFormsValidateAndProduceTypedInput() throws Exception {
        var def=chain("transform","{\"output\":{\"amount\":\"{{input.amount}}\"}}");var trigger=(com.fasterxml.jackson.databind.node.ObjectNode)def.path("nodes").get(0).path("data");trigger.put("kind","form");trigger.set("config",Json.read("{\"title\":\"Order <test>\",\"fields\":[{\"name\":\"amount\",\"label\":\"Amount\",\"type\":\"number\",\"required\":true}]}"));
        var wf=workflow(def);String path="/forms/"+wf.get("id")+"/"+wf.get("webhook_token");
        mvc.perform(get(path)).andExpect(status().isNotFound());store.db.update("update workflows set active=true where id=?",wf.get("id"));
        mvc.perform(get(path)).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Order &lt;test&gt;")));
        mvc.perform(post(path).contentType("application/x-www-form-urlencoded").param("amount","bad")).andExpect(status().isBadRequest());
        mvc.perform(post(path).contentType("application/x-www-form-urlencoded").param("amount","12.5")).andExpect(status().isOk());
        var input=Json.read(store.db.queryForObject("select input::text from executions where workflow_id=?",String.class,wf.get("id")));assertTrue(input.path("amount").isNumber());assertEquals(12.5,input.path("amount").asDouble());
    }
    @Test void restoreMakesNewInactiveVersionAndArchiveBlocksRuns() throws Exception {
        var wf=workflow(simple());UUID id=(UUID)wf.get("id");store.save(owner,id,Json.M.valueToTree(Json.map("name","Updated","active",true,"version",1,"definition",chain("transform","{\"output\":{\"new\":true}}"))));var cookie=session(owner);
        mvc.perform(post("/api/workflows/"+id+"/restore/1").cookie(cookie).header("X-Deskflow-Request","1")).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(3)).andExpect(jsonPath("$.active").value(false));
        mvc.perform(post("/api/workflows/"+id+"/duplicate").cookie(cookie).header("X-Deskflow-Request","1")).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        mvc.perform(post("/api/workflows/"+id+"/archive").cookie(cookie).header("X-Deskflow-Request","1").contentType("application/json").content("{\"archived\":true}")).andExpect(status().isOk());
        assertThrows(IllegalArgumentException.class,()->enqueue(store.workflow(owner,id)));
    }
    @Test void parentCancellationWakesWaitingChildren(){
        var child=workflow(chain("delay","{\"seconds\":60}"));UUID id=enqueue(workflow(chain("subworkflow",Json.write(Json.map("workflowId",child.get("id"))))));pump(id);pump(id);UUID childId=store.db.queryForObject("select id from executions where parent_id=?",UUID.class,id);pump(childId);pump(childId);assertEquals("WAITING",runStatus(childId));
        store.db.update("update executions set cancel_requested=true where id=?",id);pump(id);pump(childId);assertEquals("CANCELLED",runStatus(childId));
    }
    @Test void aiCompletionMapsProviderResponseWithoutPaidCall(){
        UUID credential=UUID.randomUUID();store.db.update("insert into credentials(id,owner_id,name,encrypted_value) values(?,?,?,?)",credential,owner,"Mock provider",store.secrets.encrypt("fake-provider-token"));
        UUID id=enqueue(workflow(chain("ai",Json.write(Json.map("url","https://api.openai.com/v1/chat/completions","model","fixture-model","credentialId",credential,"prompt","Summarize {{input.total}}")))));
        doReturn(Json.read("{\"status\":200,\"body\":{\"choices\":[{\"message\":{\"content\":\"Summary\"}}],\"usage\":{\"total_tokens\":4}}}")).when(actions).http(argThat(n->n.kind().equals("ai")),any(),eq(owner),eq(id));
        settle(id);assertEquals("SUCCEEDED",runStatus(id));assertEquals("Summary",output(id,"n0").path("text").asText());assertEquals(4,output(id,"n0").path("usage").path("total_tokens").asInt());
    }
    @Test void nestedPreviewPlansEveryItemWithoutCreatingChildren(){
        var child=workflow(chain("email","{\"to\":\"test@example.com\",\"subject\":\"Item {{input.item}}\"}"));
        var result=lab.simulate(owner,chain("loop",Json.write(Json.map("workflowId",child.get("id"),"items",List.of("A","B")))),Json.read("{}"),Json.read("{}"));
        assertEquals("SUCCEEDED",result.get("status"));assertEquals(2,result.get("effectCount"));assertEquals(0,store.db.queryForObject("select count(*) from executions where owner_id=?",Integer.class,owner));verifyNoInteractions(mail);
    }
    @Test void registrationSessionAndLogoutRoundTrip() throws Exception {
        String email=UUID.randomUUID()+"@example.com";
        var response=mvc.perform(post("/api/auth/register").header("X-Deskflow-Request","1").contentType("application/json").content(Json.write(Map.of("name","New User","email",email,"password","Correct-Horse-123!")))).andExpect(status().isOk()).andReturn().getResponse();
        String header=response.getHeader("Set-Cookie");assertNotNull(header);assertTrue(header.contains("HttpOnly"));assertTrue(header.contains("SameSite=Strict"));
        String token=header.split(";",2)[0].split("=",2)[1];var cookie=new Cookie("deskflow_session",token);
        mvc.perform(get("/api/auth/me").cookie(cookie)).andExpect(status().isOk()).andExpect(jsonPath("$.email").value(email));
        assertEquals(0,store.db.queryForObject("select count(*) from sessions where token_hash=?",Integer.class,token));
        mvc.perform(post("/api/auth/logout").header("X-Deskflow-Request","1").cookie(cookie)).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").cookie(cookie)).andExpect(status().isUnauthorized());
    }
}
