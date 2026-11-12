package dev.liangwen.authgateway.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
        "auth-gateway.production-safety.enabled=false",
        "spring.security.oauth2.client.registration.google.client-id=preview-client",
        "spring.security.oauth2.client.registration.google.client-secret=preview-secret"
})
@AutoConfigureMockMvc
class LoginControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void rendersPublicLoginWithOnlyTheConfiguredProvider() throws Exception {
        MvcResult result = mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Welcome back.")))
                .andExpect(content().string(containsString("href=\"/oauth2/authorization/google\"")))
                .andExpect(content().string(not(containsString("Continue with GitHub"))))
                .andExpect(content().string(not(containsString("type=\"password\""))))
                .andReturn();
        savePreview("login", result);
    }

    @Test
    void servesStylesWithoutAuthenticationAndKeepsApiUnauthorized() throws Exception {
        mockMvc.perform(get("/auth.css"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(".identity-layout")));
        mockMvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void usesRealGoogleAuthorizationEndpoint() throws Exception {
        MvcResult result = mockMvc.perform(get("/oauth2/authorization/google"))
                .andExpect(status().isFound())
                .andReturn();
        assertThat(result.getResponse().getRedirectedUrl())
                .startsWith("https://accounts.google.com/o/oauth2/v2/auth?")
                .contains("client_id=preview-client", "response_type=code", "state=");
    }

    @Test
    void showsSafeRecoverableFailureWithoutEchoingQueryDetails() throws Exception {
        MvcResult result = mockMvc.perform(get("/login").param("error", "<script>alert('secret')</script>"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("We couldn't sign you in.")))
                .andExpect(content().string(containsString("role=\"alert\"")))
                .andExpect(content().string(not(containsString("alert('secret')"))))
                .andReturn();
        savePreview("login-error", result);
    }

    @Test
    void signedInUsersSkipLogin() throws Exception {
        mockMvc.perform(get("/login").with(oidcLogin()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/"));
    }

    @Test
    void preservesDownstreamAuthorizationAcrossLoginAndProviderSelection() throws Exception {
        MvcResult result = mockMvc.perform(get("/oauth2/authorize")
                        .queryParam("response_type", "code")
                        .queryParam("client_id", "job-crm-local")
                        .queryParam("scope", "openid profile email")
                        .queryParam("redirect_uri", "http://localhost:19086/job/login/oauth2/code/auth-gateway")
                        .queryParam("state", "downstream-state")
                        .queryParam("nonce", "downstream-nonce"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/login"))
                .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        SavedRequest saved = (SavedRequest) session.getAttribute("SPRING_SECURITY_SAVED_REQUEST");
        assertThat(saved.getRedirectUrl()).contains("/oauth2/authorize", "state=downstream-state");
        mockMvc.perform(get("/login").session(session)).andExpect(status().isOk());
        mockMvc.perform(get("/oauth2/authorization/google").session(session)).andExpect(status().isFound());
        assertThat(session.getAttribute("SPRING_SECURITY_SAVED_REQUEST")).isSameAs(saved);
    }

    @Test
    void logoutRequiresCsrfAndInvalidatesGatewaySession() throws Exception {
        mockMvc.perform(post("/logout").with(oidcLogin())).andExpect(status().isForbidden());
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(post("/logout").session(session).with(csrf()).with(oidcLogin()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/signed-out"));
        assertThat(session.isInvalid()).isTrue();
        mockMvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
        MvcResult result = mockMvc.perform(get("/signed-out"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Back to sign in")))
                .andExpect(content().string(containsString("not your Google session")))
                .andReturn();
        savePreview("signed-out", result);
    }

    @Test
    void rendersWorkspaceWithEscapedAccountAndCsrfProtectedSignOut() throws Exception {
        MvcResult result = mockMvc.perform(get("/").with(oidcLogin().idToken(token -> token
                        .claim("name", "Alex Morgan")
                        .claim("email", "alex@example.com"))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Your apps")))
                .andExpect(content().string(containsString("name=\"_csrf\"")))
                .andReturn();
        savePreview("workspace", result);
        mockMvc.perform(get("/").with(oidcLogin().idToken(token -> token.claim("name", "<script>unsafe</script>"))))
                .andExpect(content().string(not(containsString("<script>unsafe</script>"))))
                .andExpect(content().string(containsString("&lt;script&gt;unsafe&lt;/script&gt;")));
    }

    static void savePreview(String name, MvcResult result) throws Exception {
        String directory = System.getProperty("auth.ui.previewDir");
        if (directory == null || directory.isBlank()) {
            return;
        }
        Path output = Path.of(directory);
        Files.createDirectories(output);
        Files.writeString(output.resolve(name + ".html"), result.getResponse().getContentAsString());
    }
}
