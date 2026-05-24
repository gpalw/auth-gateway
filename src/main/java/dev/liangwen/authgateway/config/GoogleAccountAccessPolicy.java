package dev.liangwen.authgateway.config;

import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.util.StringUtils;

public final class GoogleAccountAccessPolicy {

    private final IdentityProperties.Access access;

    public GoogleAccountAccessPolicy(IdentityProperties.Access access) {
        this.access = access;
    }

    public void assertAllowed(OidcUser googleUser) {
        String email = googleUser.getEmail();
        if (!StringUtils.hasText(email)) {
            throw accessDenied("Google account did not provide an email address");
        }
        if (access.requiresVerifiedEmail() && !Boolean.TRUE.equals(googleUser.getEmailVerified())) {
            throw accessDenied("Google account email must be verified");
        }
        if (access.hasAllowlist() && !access.allows(email)) {
            throw accessDenied("Google account is not allowed to use this gateway");
        }
    }

    private static OAuth2AuthenticationException accessDenied(String description) {
        OAuth2Error error = new OAuth2Error(OAuth2ErrorCodes.ACCESS_DENIED, description, null);
        return new OAuth2AuthenticationException(error, description);
    }
}
