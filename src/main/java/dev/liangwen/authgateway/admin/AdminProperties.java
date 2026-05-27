package dev.liangwen.authgateway.admin;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "admin")
public record AdminProperties(boolean enabled, Inventory inventory) {

    public AdminProperties {
        inventory = inventory == null ? new Inventory(true, true, true, List.of()) : inventory;
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

    public record ManualService(String id, String name, String url, String port, String notes) {
    }
}
