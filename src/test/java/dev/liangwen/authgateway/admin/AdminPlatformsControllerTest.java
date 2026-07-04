package dev.liangwen.authgateway.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.liangwen.authgateway.platform.PlatformRegistrationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "auth-gateway.production-safety.enabled=false",
        "admin.enabled=true",
        "admin.inventory.docker-enabled=false",
        "admin.inventory.systemd-enabled=false",
        "admin.inventory.ports-enabled=false",
        "identity.issuer=https://auth.example.com",
        "identity.apps[0].id=job-crm",
        "identity.apps[0].name=Job CRM",
        "identity.apps[0].description=Jobs",
        "identity.apps[0].url=https://tools.example.com/job/",
        "identity.apps[0].enabled=true",
        "identity.clients[0].client-id=job-crm",
        "identity.clients[0].client-secret=secret",
        "identity.clients[0].redirect-uris[0]=https://tools.example.com/job/login/oauth2/code/auth-gateway",
        "identity.clients[0].post-logout-redirect-uris[0]=https://tools.example.com/job/"
})
@AutoConfigureMockMvc
class AdminPlatformsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PlatformRegistrationRepository registrations;

    @Autowired
    private RegisteredClientRepository registeredClients;

    @Test
    void listsSeededPlatforms() throws Exception {
        mockMvc.perform(get("/admin/platforms"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Platform Registry")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Launch URL")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Job CRM")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("https://tools.example.com/job/")));
    }

    @Test
    void createsPlatformAndMakesItAvailableAsOidcClient() throws Exception {
        mockMvc.perform(post("/admin/platforms")
                        .with(csrf())
                        .param("clientId", "billing")
                        .param("name", "Online Billing")
                        .param("description", "Money tools")
                        .param("homeUrl", "https://tools.example.com/billing/")
                        .param("clientSecret", "plain-secret")
                        .param("redirectUrisText", "https://tools.example.com/billing/login/oauth2/code/auth-gateway")
                        .param("postLogoutRedirectUrisText", "https://tools.example.com/billing/")
                        .param("enabled", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/platforms"));

        assertThat(registrations.findByClientId("billing")).isPresent();
        assertThat(registeredClients.findByClientId("billing")).isNotNull();
    }

    @Test
    void showsValidationErrorWhenClientIdAlreadyExists() throws Exception {
        mockMvc.perform(post("/admin/platforms")
                        .with(csrf())
                        .param("clientId", "job-crm")
                        .param("name", "Duplicate Job CRM")
                        .param("description", "Duplicate")
                        .param("homeUrl", "https://tools.example.com/job/")
                        .param("clientSecret", "plain-secret")
                        .param("redirectUrisText", "https://tools.example.com/job/login/oauth2/code/auth-gateway")
                        .param("postLogoutRedirectUrisText", "https://tools.example.com/job/")
                        .param("enabled", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Client id already exists: job-crm")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Duplicate Job CRM")));
    }
}
