package dev.liangwen.authgateway.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
        "identity.clients[0].client-id=job-crm",
        "identity.clients[0].client-secret=secret",
        "identity.clients[0].redirect-uris[0]=https://job.example.com/login/oauth2/code/auth-gateway",
        "identity.clients[0].post-logout-redirect-uris[0]=https://job.example.com"
})
@AutoConfigureMockMvc
class MeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void returnsUnauthorizedWhenNotLoggedIn() throws Exception {
        mockMvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returnsCurrentUserWhenLoggedIn() throws Exception {
        mockMvc.perform(get("/api/me")
                        .with(oidcLogin().idToken(token -> token
                                .claim("sub", "internal-user-1")
                                .claim("user_id", "internal-user-1")
                                .claim("email", "wen@example.com")
                                .claim("name", "Wen Liang")
                                .claim("picture", "https://avatar.example/wen.png"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("internal-user-1"))
                .andExpect(jsonPath("$.email").value("wen@example.com"))
                .andExpect(jsonPath("$.displayName").value("Wen Liang"))
                .andExpect(jsonPath("$.avatarUrl").value("https://avatar.example/wen.png"));
    }
}
