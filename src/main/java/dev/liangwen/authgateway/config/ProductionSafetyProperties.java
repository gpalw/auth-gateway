package dev.liangwen.authgateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "auth-gateway.production-safety")
public record ProductionSafetyProperties(Boolean enabled) {

    public ProductionSafetyProperties {
        enabled = enabled == null ? true : enabled;
    }

    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }
}
