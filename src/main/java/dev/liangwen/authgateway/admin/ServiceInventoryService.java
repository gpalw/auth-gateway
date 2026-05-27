package dev.liangwen.authgateway.admin;

import dev.liangwen.authgateway.config.IdentityProperties;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ServiceInventoryService {

    private final IdentityProperties identity;
    private final AdminProperties admin;
    private final List<ServiceCollector> collectors;

    public ServiceInventoryService(
            IdentityProperties identity,
            AdminProperties admin,
            List<ServiceCollector> collectors) {
        this.identity = identity;
        this.admin = admin;
        this.collectors = List.copyOf(collectors);
    }

    public ServiceInventory inventory() {
        List<ServiceInventoryItem> items = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Map<String, IdentityProperties.Client> clientsById = identity.clients().stream()
                .collect(Collectors.toMap(IdentityProperties.Client::clientId, Function.identity(), (first, second) -> first));

        identity.enabledApps().forEach(app -> items.add(configuredApp(app, clientsById.get(app.id()))));
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

    private static ServiceInventoryItem configuredApp(
            IdentityProperties.App app,
            IdentityProperties.Client client) {
        return new ServiceInventoryItem(
                "config:" + app.id(),
                app.name(),
                ServiceSource.CONFIG,
                ServiceStatus.UNKNOWN,
                app.url(),
                host(app.url()),
                port(app.url()),
                client == null ? AuthType.PLAIN : AuthType.SSO,
                client == null ? "" : client.clientId(),
                client == null ? List.of() : client.redirectUris(),
                app.description(),
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
