package dev.liangwen.authgateway.platform;

import dev.liangwen.authgateway.config.ProductionSafetyGuard;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ProductionClientSecretPolicy {

    private static final Set<String> DEVELOPMENT_SECRETS = Set.of(
            "dev-job-crm-secret", "dev-interview-secret", "dev-online-billing-secret");

    private final ProductionSafetyGuard productionSafety;

    public ProductionClientSecretPolicy(ProductionSafetyGuard productionSafety) {
        this.productionSafety = productionSafety;
    }

    public void validate(PlatformRegistration registration) {
        if (!productionSafety.isEnforced() || !registration.enabled()) {
            return;
        }
        String normalized = registration.clientSecret() == null
                ? "" : registration.clientSecret().trim().toLowerCase(Locale.ROOT);
        while (normalized.startsWith("{noop}")) {
            normalized = normalized.substring("{noop}".length()).trim();
        }
        if (!StringUtils.hasText(normalized)
                || DEVELOPMENT_SECRETS.contains(normalized)
                || normalized.startsWith("replace-with-")
                || normalized.startsWith("your-")) {
            throw new IllegalArgumentException("Enabled production client '" + registration.clientId()
                    + "' requires a non-placeholder client secret; update its stored registration or disable it");
        }
    }
}
