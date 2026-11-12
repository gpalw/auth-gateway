package dev.liangwen.authgateway.config;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
class ProductionSafetyGuard {

    private final IdentityProperties identity;
    private final ProductionSafetyProperties safety;
    private final Environment environment;

    ProductionSafetyGuard(
            IdentityProperties identity,
            ProductionSafetyProperties safety,
            Environment environment) {
        this.identity = identity;
        this.safety = safety;
        this.environment = environment;
    }

    @PostConstruct
    void validate() {
        if (!safety.isEnabled() || !isProductionMode()) {
            return;
        }

        List<String> failures = new ArrayList<>();
        if (!identity.access().hasAllowlist()) {
            failures.add("Production issuer requires ALLOWED_EMAILS or ALLOWED_DOMAINS");
        }
        if (!identity.signingKey().isConfigured()) {
            failures.add("Production issuer requires persistent JWT signing key");
        }
        if (!environment.getProperty("server.servlet.session.cookie.secure", Boolean.class, false)) {
            failures.add("Production issuer requires SESSION_COOKIE_SECURE=true");
        }
        if (environment.getProperty("spring.h2.console.enabled", Boolean.class, false)) {
            failures.add("Production issuer cannot use the H2 console");
        }
        if (!isPublicHttpsIssuer(identity.issuer())) {
            failures.add("Production issuer must use HTTPS and cannot be localhost");
        }

        if (!failures.isEmpty()) {
            throw new IllegalStateException("Unsafe auth-gateway production configuration: "
                    + String.join("; ", failures));
        }
    }

    private boolean isProductionMode() {
        return isProductionFlag(environment.getProperty("APP_ENV"))
                || isProductionFlag(environment.getProperty("AUTH_GATEWAY_ENV"))
                || isPublicHttpsIssuer(identity.issuer());
    }

    private static boolean isProductionFlag(String value) {
        return StringUtils.hasText(value) && "production".equalsIgnoreCase(value.trim());
    }

    private static boolean isPublicHttpsIssuer(String issuer) {
        URI uri = parseUri(issuer);
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())) {
            return false;
        }
        String host = uri.getHost();
        return StringUtils.hasText(host) && !isLocalHost(host);
    }

    private static boolean isLocalHost(String host) {
        String normalized = host.trim().toLowerCase();
        return "localhost".equals(normalized)
                || "127.0.0.1".equals(normalized)
                || "::1".equals(normalized)
                || "[::1]".equals(normalized);
    }

    private static URI parseUri(String issuer) {
        if (!StringUtils.hasText(issuer)) {
            return null;
        }
        try {
            return URI.create(issuer.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
