package dev.deskflow;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class SocialProvidersTest {
    @Test void missingCredentialsKeepPasswordLoginAvailable(){
        var providers=new SocialProviders(new MockEnvironment(),"http://127.0.0.1:5174");
        assertFalse(providers.iterator().hasNext());assertNull(providers.findByRegistrationId("google"));
    }
    @Test void providersUseFixedCallbacksAndMinimalScopes(){
        var env=new MockEnvironment().withProperty("GOOGLE_CLIENT_ID","google-test").withProperty("GOOGLE_CLIENT_SECRET","test-secret")
            .withProperty("GITHUB_CLIENT_ID","github-test").withProperty("GITHUB_CLIENT_SECRET","test-secret");
        var providers=new SocialProviders(env,"http://127.0.0.1:5174/");
        assertEquals("http://127.0.0.1:5174/api/auth/oauth2/callback/google",providers.findByRegistrationId("google").getRedirectUri());
        assertTrue(providers.findByRegistrationId("google").getScopes().contains("openid"));
        assertEquals(java.util.Set.of("read:user","user:email"),providers.findByRegistrationId("github").getScopes());
    }
    @Test void rejectsUntrustedRedirectOrigins(){
        for(String url:java.util.List.of("https://user:password@example.com","https://example.com/?next=evil","javascript:alert(1)","https://example.com/path"))
            assertThrows(IllegalArgumentException.class,()->new SocialProviders(new MockEnvironment(),url));
    }
}
