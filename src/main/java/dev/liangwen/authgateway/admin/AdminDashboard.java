package dev.liangwen.authgateway.admin;

import dev.liangwen.authgateway.platform.PlatformRegistration;
import java.util.List;
import java.util.Map;

public record AdminDashboard(
        Map<String, Object> runtime,
        ServiceInventory inventory,
        List<PlatformRegistration> platforms) {

    public AdminDashboard {
        runtime = Map.copyOf(runtime);
        platforms = List.copyOf(platforms);
    }

    public int serviceCount() {
        return inventory.items().size();
    }

    public int runningServiceCount() {
        return (int) inventory.items().stream()
                .filter(item -> item.status() == ServiceStatus.RUNNING)
                .count();
    }

    public int ssoServiceCount() {
        return (int) inventory.items().stream()
                .filter(item -> item.authType() == AuthType.SSO)
                .count();
    }

    public int plainServiceCount() {
        return (int) inventory.items().stream()
                .filter(item -> item.authType() == AuthType.PLAIN)
                .count();
    }

    public int platformCount() {
        return platforms.size();
    }

    public int enabledPlatformCount() {
        return (int) platforms.stream()
                .filter(PlatformRegistration::enabled)
                .count();
    }

    public boolean hasWarnings() {
        return !inventory.errors().isEmpty();
    }

    public String runtimeValue(String key) {
        Object value = runtime.get(key);
        if (value == null || value.toString().isBlank()) {
            return "Not reported";
        }
        return value.toString();
    }

    public String databaseLabel() {
        return switch (runtimeValue("database").toLowerCase()) {
            case "postgresql" -> "PostgreSQL";
            case "h2" -> "H2";
            case "other" -> "Other";
            default -> "Unknown";
        };
    }

    public String revisionLabel() {
        String revision = runtimeValue("revision");
        if ("Not reported".equals(revision) || revision.length() <= 12) {
            return revision;
        }
        return revision.substring(0, 12);
    }

    public String booleanLabel(String key) {
        Object value = runtime.get(key);
        if (value instanceof Boolean flag) {
            return flag ? "Yes" : "No";
        }
        return "Not reported";
    }

    public String readinessLabel() {
        boolean databaseReady = "postgresql".equalsIgnoreCase(runtimeValue("database"));
        boolean allowlistReady = Boolean.TRUE.equals(runtime.get("allowlistConfigured"));
        boolean signingKeyReady = Boolean.TRUE.equals(runtime.get("signingKeyConfigured"));
        return databaseReady && allowlistReady && signingKeyReady ? "Ready" : "Needs review";
    }

    public List<ServiceInventoryItem> servicePreview() {
        return inventory.items().stream()
                .limit(8)
                .toList();
    }

    public List<PlatformRegistration> platformPreview() {
        return platforms.stream()
                .limit(6)
                .toList();
    }
}
