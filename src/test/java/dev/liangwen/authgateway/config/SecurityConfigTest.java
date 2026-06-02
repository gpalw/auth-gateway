package dev.liangwen.authgateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "identity.issuer=https://auth.example.com",
        "identity.apps[0].id=job-crm",
        "identity.apps[0].name=Job CRM",
        "identity.apps[0].description=Jobs",
        "identity.apps[0].url=https://job.example.com",
        "identity.apps[0].enabled=true",
        "identity.clients[0].client-id=job-crm",
        "identity.clients[0].client-secret=secret",
        "identity.clients[0].redirect-uris[0]=https://job.example.com/login/oauth2/code/auth-gateway",
        "identity.clients[0].post-logout-redirect-uris[0]=https://job.example.com"
})
@AutoConfigureMockMvc
class SecurityConfigTest {

    @Autowired
    private RegisteredClientRepository clients;

    @Autowired
    private AuthorizationServerSettings settings;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void createsRegisteredClientsFromIdentityProperties() {
        var client = clients.findByClientId("job-crm");

        assertThat(client).isNotNull();
        assertThat(client.getRedirectUris())
                .containsExactly("https://job.example.com/login/oauth2/code/auth-gateway");
        assertThat(client.getPostLogoutRedirectUris()).containsExactly("https://job.example.com");
        assertThat(client.getScopes()).contains(OidcScopes.OPENID, OidcScopes.PROFILE, OidcScopes.EMAIL);
    }

    @Test
    void configuresIssuer() {
        assertThat(settings.getIssuer()).isEqualTo("https://auth.example.com");
    }

    @Test
    void redirectsUnauthenticatedAuthorizeRequestsToGoogleLogin() throws Exception {
        mockMvc.perform(get("/oauth2/authorize")
                        .param("response_type", "code")
                        .param("client_id", "job-crm")
                        .param("scope", "openid profile email")
                        .param("redirect_uri", "https://job.example.com/login/oauth2/code/auth-gateway")
                        .param("state", "test-state")
                        .param("nonce", "test-nonce"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/oauth2/authorization/google"));
    }
}
