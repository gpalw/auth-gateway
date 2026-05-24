package dev.liangwen.authgateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class IdentityPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class)
            .withPropertyValues(
                    "identity.issuer=https://auth.example.com",
                    "identity.apps[0].id=job-crm",
                    "identity.apps[0].name=Job CRM",
                    "identity.apps[0].description=Jobs",
                    "identity.apps[0].url=https://job.example.com",
                    "identity.apps[0].enabled=true",
                    "identity.clients[0].client-id=job-crm",
                    "identity.clients[0].client-secret=secret",
                    "identity.clients[0].redirect-uris[0]=https://job.example.com/login/oauth2/code/auth-gateway",
                    "identity.clients[0].post-logout-redirect-uris[0]=https://job.example.com",
                    "identity.access.allowed-emails=wen@example.com,ADMIN@example.com",
                    "identity.access.allowed-domains=liangwendev.com,@example.org",
                    "identity.access.require-verified-email=true",
                    "identity.signing-key.private-key-location=/run/secrets/auth-gateway-private-key.pem",
                    "identity.signing-key.key-id=prod-main");

    @Test
    void bindsAppsAndClients() {
        contextRunner.run(context -> {
            IdentityProperties properties = context.getBean(IdentityProperties.class);

            assertThat(properties.issuer()).isEqualTo("https://auth.example.com");
            assertThat(properties.enabledApps()).extracting(IdentityProperties.App::id).containsExactly("job-crm");
            assertThat(properties.clients()).hasSize(1);
            assertThat(properties.clients().getFirst().clientId()).isEqualTo("job-crm");
            assertThat(properties.clients().getFirst().redirectUris())
                    .containsExactly("https://job.example.com/login/oauth2/code/auth-gateway");
            assertThat(properties.access().allowedEmails()).containsExactly("wen@example.com", "admin@example.com");
            assertThat(properties.access().allowedDomains()).containsExactly("liangwendev.com", "example.org");
            assertThat(properties.access().requiresVerifiedEmail()).isTrue();
            assertThat(properties.signingKey().privateKeyLocation())
                    .isEqualTo("/run/secrets/auth-gateway-private-key.pem");
            assertThat(properties.signingKey().keyId()).isEqualTo("prod-main");
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(IdentityProperties.class)
    static class TestConfig {
    }
}
