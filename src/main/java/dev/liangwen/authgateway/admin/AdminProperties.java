package dev.liangwen.authgateway.admin;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "admin")
public record AdminProperties(boolean enabled, Inventory inventory, Access access) {

    public AdminProperties {
        inventory = inventory == null ? new Inventory(true, true, true, List.of()) : inventory;
        access = access == null ? new Access(List.of(), null) : access;
    }

    public record Inventory(
            boolean dockerEnabled,
            boolean systemdEnabled,
            boolean portsEnabled,
            List<ManualService> services) {

        public Inventory {
            services = services == null ? List.of() : List.copyOf(services);
        }
    }

    public record Access(List<String> allowedProxyIps, String accessToken) {

        public Access {
            allowedProxyIps = normalize(allowedProxyIps);
            accessToken = StringUtils.hasText(accessToken) ? accessToken.trim() : null;
        }

        public boolean hasAccessToken() {
            return accessToken != null;
        }

        public boolean tokenMatches(String value) {
            return accessToken != null && accessToken.equals(value);
        }

        private static List<String> normalize(List<String> values) {
            if (values == null) {
                return List.of();
            }
            return values.stream()
                    .map(value -> StringUtils.hasText(value) ? value.trim() : null)
                    .filter(StringUtils::hasText)
                    .distinct()
                    .toList();
        }
    }

    public record ManualService(String id, String name, String url, String port, String notes) {
    }
}
