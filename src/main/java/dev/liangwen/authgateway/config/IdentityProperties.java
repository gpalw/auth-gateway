package dev.liangwen.authgateway.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "identity")
public record IdentityProperties(String issuer, List<App> apps, List<Client> clients) {

    public IdentityProperties {
        apps = apps == null ? List.of() : List.copyOf(apps);
        clients = clients == null ? List.of() : List.copyOf(clients);
    }

    public List<App> enabledApps() {
        return apps.stream()
                .filter(App::enabled)
                .toList();
    }

    public record App(String id, String name, String description, String url, boolean enabled) {
    }

    public record Client(
            String clientId,
            String clientSecret,
            List<String> redirectUris,
            List<String> postLogoutRedirectUris) {

        public Client {
            redirectUris = redirectUris == null ? List.of() : List.copyOf(redirectUris);
            postLogoutRedirectUris = postLogoutRedirectUris == null ? List.of() : List.copyOf(postLogoutRedirectUris);
        }
    }
}
