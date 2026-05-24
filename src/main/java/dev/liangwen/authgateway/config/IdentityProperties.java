package dev.liangwen.authgateway.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "identity")
public record IdentityProperties(
        String issuer,
        List<App> apps,
        List<Client> clients,
        Access access,
        SigningKey signingKey) {

    public IdentityProperties {
        apps = apps == null ? List.of() : List.copyOf(apps);
        clients = clients == null ? List.of() : List.copyOf(clients);
        access = access == null ? new Access(List.of(), List.of(), true) : access;
        signingKey = signingKey == null ? new SigningKey(null, null, null) : signingKey;
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

    public record Access(List<String> allowedEmails, List<String> allowedDomains, Boolean requireVerifiedEmail) {

        public Access {
            allowedEmails = normalizedList(allowedEmails);
            allowedDomains = normalizedDomains(allowedDomains);
            requireVerifiedEmail = requireVerifiedEmail == null ? true : requireVerifiedEmail;
        }

        public boolean requiresVerifiedEmail() {
            return Boolean.TRUE.equals(requireVerifiedEmail);
        }

        public boolean hasAllowlist() {
            return !allowedEmails.isEmpty() || !allowedDomains.isEmpty();
        }

        public boolean allows(String email) {
            String normalizedEmail = normalize(email);
            if (normalizedEmail == null) {
                return false;
            }
            if (allowedEmails.contains(normalizedEmail)) {
                return true;
            }
            int separator = normalizedEmail.indexOf('@');
            return separator > -1 && allowedDomains.contains(normalizedEmail.substring(separator + 1));
        }
    }

    public record SigningKey(String privateKeyLocation, String privateKey, String keyId) {

        public SigningKey {
            privateKeyLocation = blankToNull(privateKeyLocation);
            privateKey = blankToNull(privateKey);
            keyId = blankToNull(keyId);
        }

        public boolean isConfigured() {
            return privateKeyLocation != null || privateKey != null;
        }
    }

    private static List<String> normalizedDomains(List<String> values) {
        return normalizedList(values).stream()
                .map(value -> value.startsWith("@") ? value.substring(1) : value)
                .filter(StringUtils::hasText)
                .toList();
    }

    private static List<String> normalizedList(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .map(IdentityProperties::normalize)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
    }

    private static String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim().toLowerCase() : null;
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
