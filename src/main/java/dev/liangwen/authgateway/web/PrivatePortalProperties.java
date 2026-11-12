package dev.liangwen.authgateway.web;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("private-portal")
public record PrivatePortalProperties(boolean enabled, String ownerEmail, Map<String, String> categories) {
    public PrivatePortalProperties {
        ownerEmail = ownerEmail == null ? "lwxyq084891@gmail.com" : ownerEmail;
        categories = categories == null ? Map.of() : Map.copyOf(categories);
    }
}
