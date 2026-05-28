package dev.liangwen.authgateway.platform;

import java.util.List;
import java.util.UUID;

public record PlatformRegistration(
        UUID id,
        String clientId,
        String clientSecret,
        String name,
        String description,
        String homeUrl,
        List<String> redirectUris,
        List<String> postLogoutRedirectUris,
        boolean enabled) {

    public PlatformRegistration {
        redirectUris = redirectUris == null ? List.of() : List.copyOf(redirectUris);
        postLogoutRedirectUris = postLogoutRedirectUris == null ? List.of() : List.copyOf(postLogoutRedirectUris);
    }
}
