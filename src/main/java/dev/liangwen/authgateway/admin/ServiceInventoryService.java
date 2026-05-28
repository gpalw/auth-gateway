package dev.liangwen.authgateway.admin;

import dev.liangwen.authgateway.platform.PlatformRegistration;
import dev.liangwen.authgateway.platform.PlatformRegistrationService;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ServiceInventoryService {

    private final PlatformRegistrationService platforms;
    private final AdminProperties admin;
    private final List<ServiceCollector> collectors;

    public ServiceInventoryService(
            PlatformRegistrationService platforms,
            AdminProperties admin,
            List<ServiceCollector> collectors) {
        this.platforms = platforms;
        this.admin = admin;
        this.collectors = List.copyOf(collectors);
    }

    public ServiceInventory inventory() {
        List<ServiceInventoryItem> items = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        platforms.allPlatforms().forEach(platform -> items.add(configuredApp(platform)));
        admin.inventory().services().forEach(service -> items.add(manualService(service)));

        for (ServiceCollector collector : collectors) {
            try {
                collector.collect().stream()
                        .map(ServiceInventoryService::runtimeService)
                        .forEach(items::add);
            } catch (Exception ex) {
                errors.add(ex.getMessage());
            }
        }

        return new ServiceInventory(Instant.now(), items, errors);
    }

    private static ServiceInventoryItem configuredApp(PlatformRegistration platform) {
        return new ServiceInventoryItem(
                "platform:" + platform.clientId(),
                platform.name(),
                ServiceSource.CONFIG,
                ServiceStatus.UNKNOWN,
                platform.homeUrl(),
                host(platform.homeUrl()),
                port(platform.homeUrl()),
                platform.enabled() ? AuthType.SSO : AuthType.UNKNOWN,
                platform.clientId(),
                platform.redirectUris(),
                platform.description(),
                "");
    }

    private static ServiceInventoryItem manualService(AdminProperties.ManualService service) {
        return new ServiceInventoryItem(
                "manual:" + valueOrFallback(service.id(), service.name()),
                valueOrFallback(service.name(), service.id()),
                ServiceSource.CONFIG,
                ServiceStatus.UNKNOWN,
                valueOrFallback(service.url(), ""),
                host(service.url()),
                parsePort(service.port(), port(service.url())),
                AuthType.PLAIN,
                "",
                List.of(),
                valueOrFallback(service.notes(), ""),
                "");
    }

    private static ServiceInventoryItem runtimeService(CollectedService service) {
        return new ServiceInventoryItem(
                service.source().name().toLowerCase() + ":" + service.name() + ":" + valueOrFallback(service.port(), ""),
                service.name(),
                service.source(),
                service.status(),
                "",
                valueOrFallback(service.listenAddress(), ""),
                service.port(),
                AuthType.UNKNOWN,
                "",
                List.of(),
                valueOrFallback(service.description(), ""),
                valueOrFallback(service.raw(), ""));
    }

    private static String host(String url) {
        if (!StringUtils.hasText(url)) {
            return "";
        }
        try {
            URI uri = URI.create(url);
            return valueOrFallback(uri.getHost(), "");
        } catch (Exception ex) {
            return "";
        }
    }

    private static Integer port(String url) {
        if (!StringUtils.hasText(url)) {
            return null;
        }
        try {
            URI uri = URI.create(url);
            if (uri.getPort() > -1) {
                return uri.getPort();
            }
            if ("https".equalsIgnoreCase(uri.getScheme())) {
                return 443;
            }
            if ("http".equalsIgnoreCase(uri.getScheme())) {
                return 80;
            }
            return null;
        } catch (Exception ex) {
            return null;
        }
    }

    private static Integer parsePort(String value, Integer fallback) {
        if (!StringUtils.hasText(value)) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static String valueOrFallback(Object value, String fallback) {
        return value == null ? fallback : value.toString();
    }
}
