package dev.liangwen.authgateway.web;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

public final class PrivatePortalAccess {
    private PrivatePortalAccess() {}
    public static boolean allowed(Authentication authentication, PrivatePortalProperties properties) {
        return authentication instanceof OAuth2AuthenticationToken token
                && token.isAuthenticated()
                && "google".equals(token.getAuthorizedClientRegistrationId())
                && token.getPrincipal() instanceof OidcUser user
                && Boolean.TRUE.equals(user.getEmailVerified())
                && properties.ownerEmail().equalsIgnoreCase(user.getEmail());
    }
}
