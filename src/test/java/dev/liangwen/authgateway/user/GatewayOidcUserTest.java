package dev.liangwen.authgateway.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

class GatewayOidcUserTest {

    @Test
    void exposesInternalUserIdAsPrincipalNameAndSubject() {
        UUID userId = UUID.randomUUID();
        GatewayUser gatewayUser = new GatewayUser(userId, "wen@example.com", "Wen Liang", "https://avatar.example/wen.png");
        OidcUser googleUser = googleUser();

        GatewayOidcUser principal = GatewayOidcUser.from(gatewayUser, googleUser);

        assertThat(principal.getName()).isEqualTo(userId.toString());
        assertThat(principal.getSubject()).isEqualTo(userId.toString());
        assertThat(principal.getClaims())
                .containsEntry("user_id", userId.toString())
                .containsEntry("email", "wen@example.com")
                .containsEntry("name", "Wen Liang")
                .containsEntry("picture", "https://avatar.example/wen.png");
        assertThat(principal.getIdToken()).isSameAs(googleUser.getIdToken());
    }

    private static OidcUser googleUser() {
        Instant issuedAt = Instant.parse("2026-05-24T00:00:00Z");
        Instant expiresAt = issuedAt.plusSeconds(3600);
        Map<String, Object> claims = Map.of(
                "sub", "google-sub-1",
                "email", "wen@example.com",
                "name", "Wen From Google");
        OidcIdToken idToken = new OidcIdToken("token", issuedAt, expiresAt, claims);
        OidcUserInfo userInfo = new OidcUserInfo(claims);
        return new DefaultOidcUser(List.of(new SimpleGrantedAuthority("ROLE_USER")), idToken, userInfo);
    }
}
