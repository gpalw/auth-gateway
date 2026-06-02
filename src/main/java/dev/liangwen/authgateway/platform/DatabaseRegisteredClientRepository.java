package dev.liangwen.authgateway.platform;

import java.util.UUID;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.util.StringUtils;

public class DatabaseRegisteredClientRepository implements RegisteredClientRepository {

    private final PlatformRegistrationRepository registrations;

    public DatabaseRegisteredClientRepository(PlatformRegistrationRepository registrations) {
        this.registrations = registrations;
    }

    @Override
    public void save(RegisteredClient registeredClient) {
        PlatformRegistration existing = registrations.findByClientId(registeredClient.getClientId()).orElse(null);
        registrations.save(new PlatformRegistration(
                existing == null ? null : existing.id(),
                registeredClient.getClientId(),
                stripNoop(registeredClient.getClientSecret()),
                existing == null ? registeredClient.getClientId() : existing.name(),
                existing == null ? "" : existing.description(),
                existing == null ? firstOrEmpty(registeredClient.getPostLogoutRedirectUris()) : existing.homeUrl(),
                registeredClient.getRedirectUris().stream().toList(),
                registeredClient.getPostLogoutRedirectUris().stream().toList(),
                true));
    }

    @Override
    public RegisteredClient findById(String id) {
        try {
            return registrations.findById(UUID.fromString(id))
                    .filter(PlatformRegistration::enabled)
                    .map(DatabaseRegisteredClientRepository::toRegisteredClient)
                    .orElse(null);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    @Override
    public RegisteredClient findByClientId(String clientId) {
        return registrations.findByClientId(clientId)
                .filter(PlatformRegistration::enabled)
                .map(DatabaseRegisteredClientRepository::toRegisteredClient)
                .orElse(null);
    }

    private static RegisteredClient toRegisteredClient(PlatformRegistration registration) {
        RegisteredClient.Builder builder = RegisteredClient.withId(registration.id().toString())
                .clientId(registration.clientId())
                .clientSecret("{noop}" + registration.clientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)
                .scope(OidcScopes.EMAIL)
                .clientSettings(ClientSettings.builder()
                        .requireAuthorizationConsent(false)
                        .requireProofKey(false)
                        .build());

        registration.redirectUris().forEach(builder::redirectUri);
        registration.postLogoutRedirectUris().forEach(builder::postLogoutRedirectUri);
        return builder.build();
    }

    private static String stripNoop(String secret) {
        if (!StringUtils.hasText(secret)) {
            return "";
        }
        return secret.startsWith("{noop}") ? secret.substring("{noop}".length()) : secret;
    }

    private static String firstOrEmpty(Iterable<String> values) {
        for (String value : values) {
            return value;
        }
        return "";
    }
}
