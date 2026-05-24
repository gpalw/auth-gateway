package dev.liangwen.authgateway.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

class GoogleAccountAccessPolicyTest {

    @Test
    void allowsVerifiedGoogleUserWhenNoAllowlistIsConfigured() {
        GoogleAccountAccessPolicy policy = new GoogleAccountAccessPolicy(
                new IdentityProperties.Access(List.of(), List.of(), true));

        policy.assertAllowed(googleUser("wen@example.com", true));
    }

    @Test
    void allowsExactEmailCaseInsensitively() {
        GoogleAccountAccessPolicy policy = new GoogleAccountAccessPolicy(
                new IdentityProperties.Access(List.of("WEN@example.com"), List.of(), true));

        policy.assertAllowed(googleUser("wen@example.com", true));
    }

    @Test
    void allowsConfiguredEmailDomainCaseInsensitively() {
        GoogleAccountAccessPolicy policy = new GoogleAccountAccessPolicy(
                new IdentityProperties.Access(List.of(), List.of("Example.com"), true));

        policy.assertAllowed(googleUser("wen@example.com", true));
    }

    @Test
    void rejectsUnverifiedEmailWhenVerificationIsRequired() {
        GoogleAccountAccessPolicy policy = new GoogleAccountAccessPolicy(
                new IdentityProperties.Access(List.of(), List.of(), true));

        assertThatThrownBy(() -> policy.assertAllowed(googleUser("wen@example.com", false)))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .hasMessageContaining("verified");
    }

    @Test
    void rejectsGoogleUserOutsideConfiguredAllowlist() {
        GoogleAccountAccessPolicy policy = new GoogleAccountAccessPolicy(
                new IdentityProperties.Access(List.of("admin@example.com"), List.of("liangwendev.com"), true));

        assertThatThrownBy(() -> policy.assertAllowed(googleUser("wen@example.com", true)))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .hasMessageContaining("not allowed");
    }

    private static OidcUser googleUser(String email, boolean emailVerified) {
        OidcIdToken idToken = OidcIdToken.withTokenValue("id-token")
                .subject("google-sub")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .claim("email", email)
                .claim("email_verified", emailVerified)
                .build();
        return new DefaultOidcUser(List.of(), idToken);
    }
}
