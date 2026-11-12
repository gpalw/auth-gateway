package dev.liangwen.authgateway.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.liangwen.authgateway.config.IdentityProperties;
import dev.liangwen.authgateway.config.ProductionSafetyGuard;
import java.util.List;
import java.util.UUID;
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
        DatabaseRegisteredClientRepository clients = new DatabaseRegisteredClientRepository(registrations, policy(false));

        var client = clients.findByClientId("billing");

        assertThat(client).isNotNull();
        assertThat(client.getClientSecret()).isEqualTo("{noop}plain-secret");
        assertThat(client.getRedirectUris())
                .containsExactly("https://tools.example.com/billing/login/oauth2/code/auth-gateway");
        assertThat(client.getPostLogoutRedirectUris()).containsExactly("https://tools.example.com/billing/");
        assertThat(client.getScopes()).contains(OidcScopes.OPENID, OidcScopes.PROFILE, OidcScopes.EMAIL);
        assertThat(client.getClientSettings().isRequireProofKey()).isFalse();
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
        DatabaseRegisteredClientRepository clients = new DatabaseRegisteredClientRepository(registrations, policy(false));

        assertThat(clients.findByClientId("billing")).isNull();
    }

    @Test
    void neverExposesPersistedDefaultSecretsForProductionAuthentication() {
        InMemoryPlatformRegistrationRepository registrations = new InMemoryPlatformRegistrationRepository();
        PlatformRegistration platform = registrations.save(new PlatformRegistration(null, "billing",
                "dev-online-billing-secret", "Billing", "", "https://billing.example.com",
                List.of("https://billing.example.com/callback"), List.of(), true));
        DatabaseRegisteredClientRepository clients = new DatabaseRegisteredClientRepository(registrations, policy(true));

        assertThat(clients.findByClientId(platform.clientId())).isNull();
        assertThat(clients.findById(platform.id().toString())).isNull();
        assertThat(registrations.findByClientId(platform.clientId()).orElseThrow().clientSecret())
                .isEqualTo("dev-online-billing-secret");
    }

    @Test
    void rejectsPlaceholderSaveThroughRegisteredClientApi() {
        InMemoryPlatformRegistrationRepository registrations = new InMemoryPlatformRegistrationRepository();
        DatabaseRegisteredClientRepository clients = new DatabaseRegisteredClientRepository(registrations, policy(true));
        var client = org.springframework.security.oauth2.server.authorization.client.RegisteredClient
                .withId(UUID.randomUUID().toString())
                .clientId("billing")
                .clientSecret("{noop}dev-online-billing-secret")
                .clientAuthenticationMethod(org.springframework.security.oauth2.core.ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(org.springframework.security.oauth2.core.AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://billing.example.com/callback")
                .build();

        assertThatThrownBy(() -> clients.save(client)).isInstanceOf(IllegalArgumentException.class);
        assertThat(registrations.findByClientId("billing")).isEmpty();
    }

    private static ProductionClientSecretPolicy policy(boolean production) {
        ProductionSafetyGuard guard = mock(ProductionSafetyGuard.class);
        when(guard.isEnforced()).thenReturn(production);
        return new ProductionClientSecretPolicy(guard);
    }
}
