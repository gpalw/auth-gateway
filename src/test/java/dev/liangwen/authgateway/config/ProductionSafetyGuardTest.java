package dev.liangwen.authgateway.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProductionSafetyGuardTest {

    @Test
    void failsWithHttpsIssuerAndEmptyAllowlist() {
        IdentityProperties identity = identity(
                "https://auth.example.com",
                new IdentityProperties.Access(List.of(), List.of(), true),
                new IdentityProperties.SigningKey("/etc/auth-gateway/auth-gateway-private-key.pem", null, "main"));

        assertThatThrownBy(() -> new ProductionSafetyGuard(identity, enabledSafety(), prodEnv()).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Production issuer requires ALLOWED_EMAILS or ALLOWED_DOMAINS");
    }

    @Test
    void allowsLocalhostDevelopmentWithEmptyAllowlist() {
        IdentityProperties identity = identity(
                "http://localhost:8080",
                new IdentityProperties.Access(List.of(), List.of(), true),
                new IdentityProperties.SigningKey(null, null, null));

        assertThatCode(() -> new ProductionSafetyGuard(identity, enabledSafety(), localEnv()).validate())
                .doesNotThrowAnyException();
    }

    @Test
    void failsProductionWhenSigningKeyIsMissing() {
        IdentityProperties identity = identity(
                "https://auth.example.com",
                new IdentityProperties.Access(List.of("owner@example.com"), List.of(), true),
                new IdentityProperties.SigningKey(null, null, null));

        assertThatThrownBy(() -> new ProductionSafetyGuard(identity, enabledSafety(), prodEnv()).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Production issuer requires persistent JWT signing key");
    }

    @Test
    void failsProductionWhenSecureCookieIsDisabled() {
        IdentityProperties identity = identity(
                "https://auth.example.com",
                new IdentityProperties.Access(List.of("owner@example.com"), List.of(), true),
                new IdentityProperties.SigningKey("/etc/auth-gateway/auth-gateway-private-key.pem", null, "main"));

        MockEnvironment env = prodEnv()
                .withProperty("server.servlet.session.cookie.secure", "false");

        assertThatThrownBy(() -> new ProductionSafetyGuard(identity, enabledSafety(), env).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Production issuer requires SESSION_COOKIE_SECURE=true");
    }

    @Test
    void failsProductionWhenH2ConsoleIsEnabled() {
        IdentityProperties identity = identity(
                "https://auth.example.com",
                new IdentityProperties.Access(List.of("owner@example.com"), List.of(), true),
                new IdentityProperties.SigningKey("/etc/auth-gateway/auth-gateway-private-key.pem", null, "main"));

        MockEnvironment env = prodEnv()
                .withProperty("spring.h2.console.enabled", "true");

        assertThatThrownBy(() -> new ProductionSafetyGuard(identity, enabledSafety(), env).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Production issuer cannot use the H2 console");
    }

    @Test
    void failsExplicitProductionWhenIssuerIsPlainHttp() {
        IdentityProperties identity = identity(
                "http://auth.example.com",
                new IdentityProperties.Access(List.of("owner@example.com"), List.of(), true),
                new IdentityProperties.SigningKey("/etc/auth-gateway/auth-gateway-private-key.pem", null, "main"));

        MockEnvironment env = prodEnv()
                .withProperty("AUTH_GATEWAY_ENV", "production");

        assertThatThrownBy(() -> new ProductionSafetyGuard(identity, enabledSafety(), env).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Production issuer must use HTTPS and cannot be localhost");
    }

    @Test
    void canBeDisabledForTargetedTests() {
        IdentityProperties identity = identity(
                "https://auth.example.com",
                new IdentityProperties.Access(List.of(), List.of(), true),
                new IdentityProperties.SigningKey(null, null, null));

        assertThatCode(() -> new ProductionSafetyGuard(identity, new ProductionSafetyProperties(false), prodEnv())
                .validate()).doesNotThrowAnyException();
    }

    private static ProductionSafetyProperties enabledSafety() {
        return new ProductionSafetyProperties(true);
    }

    private static MockEnvironment prodEnv() {
        return new MockEnvironment()
                .withProperty("server.servlet.session.cookie.secure", "true")
                .withProperty("spring.h2.console.enabled", "false");
    }

    private static MockEnvironment localEnv() {
        return new MockEnvironment()
                .withProperty("server.servlet.session.cookie.secure", "false")
                .withProperty("spring.h2.console.enabled", "false");
    }

    private static IdentityProperties identity(
            String issuer,
            IdentityProperties.Access access,
            IdentityProperties.SigningKey signingKey) {
        return new IdentityProperties(issuer, List.of(), List.of(), access, signingKey);
    }
}
