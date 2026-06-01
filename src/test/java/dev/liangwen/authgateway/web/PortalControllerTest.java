package dev.liangwen.authgateway.web;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "identity.issuer=https://auth.example.com",
        "identity.apps[0].id=job-crm",
        "identity.apps[0].name=Job CRM",
        "identity.apps[0].description=Jobs",
        "identity.apps[0].url=https://job.example.com",
        "identity.apps[0].enabled=true",
        "identity.apps[1].id=disabled",
        "identity.apps[1].name=Disabled App",
        "identity.apps[1].description=Hidden",
        "identity.apps[1].url=https://hidden.example.com",
        "identity.apps[1].enabled=false",
        "identity.clients[0].client-id=job-crm",
        "identity.clients[0].client-secret=secret",
        "identity.clients[0].redirect-uris[0]=https://job.example.com/login/oauth2/code/auth-gateway",
        "identity.clients[0].post-logout-redirect-uris[0]=https://job.example.com"
})
@AutoConfigureMockMvc
class PortalControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void redirectsAnonymousUsersToGoogleLogin() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/oauth2/authorization/google"));
    }

    @Test
    void rendersEnabledAppsForLoggedInUser() throws Exception {
        mockMvc.perform(get("/")
                        .with(oidcLogin().idToken(token -> token
                                .claim("sub", "internal-user-1")
                                .claim("email", "wen@example.com")
                                .claim("name", "Wen Liang"))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Job CRM")))
                .andExpect(content().string(containsString("https://job.example.com")))
                .andExpect(content().string(containsString("Wen Liang")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Disabled App"))));
    }

    @Test
    void logoutStopsOnPublicSignedOutPage() throws Exception {
        mockMvc.perform(post("/logout")
                        .with(csrf())
                        .with(oidcLogin()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/signed-out"));

        mockMvc.perform(get("/signed-out"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Signed out")));
    }
}
