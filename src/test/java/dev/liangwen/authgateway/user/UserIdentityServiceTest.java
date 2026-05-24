package dev.liangwen.authgateway.user;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

@DataJpaTest
@Import(UserIdentityService.class)
class UserIdentityServiceTest {

    @Autowired
    private UserIdentityService service;

    @Autowired
    private ExternalAccountRepository externalAccounts;

    @Test
    void createsUserAndExternalAccountForFirstGoogleLogin() {
        GatewayUser user = service.upsertGoogleUser(
                "google-sub-1",
                "wen@example.com",
                "Wen Liang",
                "https://avatar.example/wen.png");

        assertThat(user.id()).isNotNull();
        assertThat(user.email()).isEqualTo("wen@example.com");
        assertThat(user.displayName()).isEqualTo("Wen Liang");
        assertThat(user.avatarUrl()).isEqualTo("https://avatar.example/wen.png");

        ExternalAccount account = externalAccounts.findByProviderAndProviderSubject("google", "google-sub-1")
                .orElseThrow();
        assertThat(account.getUser().getId()).isEqualTo(user.id());
        assertThat(account.getEmail()).isEqualTo("wen@example.com");
    }

    @Test
    void keepsSameInternalUserIdForRepeatedGoogleLogin() {
        GatewayUser first = service.upsertGoogleUser("google-sub-1", "old@example.com", "Old Name", null);
        GatewayUser second = service.upsertGoogleUser(
                "google-sub-1",
                "wen@example.com",
                "Wen Liang",
                "https://avatar.example/wen.png");

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.email()).isEqualTo("wen@example.com");
        assertThat(second.displayName()).isEqualTo("Wen Liang");
        assertThat(second.avatarUrl()).isEqualTo("https://avatar.example/wen.png");
    }
}
