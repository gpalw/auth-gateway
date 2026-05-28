package dev.liangwen.authgateway.platform;

import static org.assertj.core.api.Assertions.assertThat;

import dev.liangwen.authgateway.config.IdentityProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.oidc.OidcScopes;

class DatabaseRegisteredClientRepositoryTest {

    @Test
    void readsEnabledRegisteredClientFromPlatformRegistration() {
        InMemoryPlatformRegistrationRepository registrations = new InMemoryPlatformRegistrationRepository();
        PlatformRegistrationService service = new PlatformRegistrationService(registrations, new IdentityProperties(
                "https://auth.example.com",
                List.of(),
                List.of(),
                null,
                null));
        service.create(new PlatformRegistrationForm(
                "billing",
                "Online Billing",
                "Money tools",
                "https://tools.example.com/billing/",
                "plain-secret",
                "https://tools.example.com/billing/login/oauth2/code/auth-gateway",
                "https://tools.example.com/billing/",
                true));
        DatabaseRegisteredClientRepository clients = new DatabaseRegisteredClientRepository(registrations);

        var client = clients.findByClientId("billing");

        assertThat(client).isNotNull();
        assertThat(client.getClientSecret()).isEqualTo("{noop}plain-secret");
        assertThat(client.getRedirectUris())
                .containsExactly("https://tools.example.com/billing/login/oauth2/code/auth-gateway");
        assertThat(client.getPostLogoutRedirectUris()).containsExactly("https://tools.example.com/billing/");
        assertThat(client.getScopes()).contains(OidcScopes.OPENID, OidcScopes.PROFILE, OidcScopes.EMAIL);
    }

    @Test
    void ignoresDisabledPlatforms() {
        InMemoryPlatformRegistrationRepository registrations = new InMemoryPlatformRegistrationRepository();
        PlatformRegistrationService service = new PlatformRegistrationService(registrations, new IdentityProperties(
                "https://auth.example.com",
                List.of(),
                List.of(),
                null,
                null));
        service.create(new PlatformRegistrationForm(
                "billing",
                "Online Billing",
                "Money tools",
                "https://tools.example.com/billing/",
                "plain-secret",
                "https://tools.example.com/billing/login/oauth2/code/auth-gateway",
                "https://tools.example.com/billing/",
                false));
        DatabaseRegisteredClientRepository clients = new DatabaseRegisteredClientRepository(registrations);

        assertThat(clients.findByClientId("billing")).isNull();
    }
}
