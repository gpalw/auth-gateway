package dev.liangwen.authgateway.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.liangwen.authgateway.platform.PlatformRegistration;
import dev.liangwen.authgateway.platform.PlatformRegistrationService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "auth-gateway.production-safety.enabled=false",
        "spring.security.oauth2.client.registration.google.client-id=dev-google-client-id",
        "spring.security.oauth2.client.registration.google.client-secret=dev-google-client-secret"
})
@AutoConfigureMockMvc
class LoginEmptyStateTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PlatformRegistrationService platforms;

    @Test
    void unconfiguredGoogleShowsAnHonestNonInteractiveState() throws Exception {
        var result = mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Sign-in is being set up.")))
                .andExpect(content().string(not(containsString("href=\"/oauth2/authorization/google\""))))
                .andReturn();
        LoginControllerTest.savePreview("login-unconfigured", result);
    }

    @Test
    void emptyWorkspaceHasNoFakeApps() throws Exception {
        when(platforms.enabledPlatforms()).thenReturn(List.of());
        var result = mockMvc.perform(get("/").with(oidcLogin().idToken(token -> token
                        .claim("name", "Alex Morgan").claim("email", "alex@example.com"))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No apps are connected yet.")))
                .andExpect(content().string(not(containsString("class=\"application-card\""))))
                .andReturn();
        LoginControllerTest.savePreview("workspace-empty", result);
    }

    @Test
    void longAccountAndAppNamesAreRenderedWithoutTruncatingContent() throws Exception {
        when(platforms.enabledPlatforms()).thenReturn(List.of(new PlatformRegistration(
                null, "example", "not-rendered", "A very long application title for international collaboration",
                "An application with a detailed description that remains readable on a narrow screen.",
                "https://example.com", List.of(), List.of(), true)));
        var result = mockMvc.perform(get("/").with(oidcLogin().idToken(token -> token
                        .claim("name", "Alexandria Morgan-Chen")
                        .claim("email", "averylongaccountnameforlayouttesting@example.com"))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Alexandria Morgan-Chen")))
                .andExpect(content().string(not(containsString("not-rendered"))))
                .andReturn();
        LoginControllerTest.savePreview("workspace-long", result);
    }
}
