package dev.liangwen.authgateway.platform;

import dev.liangwen.authgateway.config.IdentityProperties;
import jakarta.annotation.PostConstruct;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class PlatformRegistrationService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final PlatformRegistrationRepository registrations;
    private final IdentityProperties identity;

    public PlatformRegistrationService(
            PlatformRegistrationRepository registrations,
            IdentityProperties identity) {
        this.registrations = registrations;
        this.identity = identity;
    }

    @PostConstruct
    @Transactional
    public void seedConfiguredPlatforms() {
        for (IdentityProperties.App app : identity.apps()) {
            if (registrations.existsByClientId(app.id())) {
                continue;
            }
            IdentityProperties.Client client = identity.clients().stream()
                    .filter(candidate -> candidate.clientId().equals(app.id()))
                    .findFirst()
                    .orElse(null);
            registrations.save(new PlatformRegistration(
                    null,
                    app.id(),
                    client == null ? generateSecret() : client.clientSecret(),
                    app.name(),
                    valueOrFallback(app.description(), ""),
                    app.url(),
                    client == null ? List.of() : client.redirectUris(),
                    client == null ? List.of() : client.postLogoutRedirectUris(),
                    app.enabled()));
        }
    }

    @Transactional(readOnly = true)
    public List<PlatformRegistration> allPlatforms() {
        return registrations.findAllByOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public List<PlatformRegistration> enabledPlatforms() {
        return registrations.findAllByEnabledTrueOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public PlatformRegistration get(UUID id) {
        return registrations.findById(id).orElseThrow();
    }

    @Transactional
    public PlatformRegistration create(PlatformRegistrationForm form) {
        String clientId = normalizeClientId(valueOrFallback(form.getClientId(), form.getName()));
        if (registrations.existsByClientId(clientId)) {
            throw new IllegalArgumentException("Client id already exists: " + clientId);
        }
        return registrations.save(fromForm(null, clientId, form, generateSecret()));
    }

    @Transactional
    public PlatformRegistration update(UUID id, PlatformRegistrationForm form) {
        PlatformRegistration existing = get(id);
        return registrations.save(fromForm(id, existing.clientId(), form, existing.clientSecret()));
    }

    private static PlatformRegistration fromForm(
            UUID id,
            String clientId,
            PlatformRegistrationForm form,
            String fallbackSecret) {
        return new PlatformRegistration(
                id,
                clientId,
                valueOrFallback(form.getClientSecret(), fallbackSecret),
                valueOrFallback(form.getName(), clientId),
                valueOrFallback(form.getDescription(), ""),
                requireText(form.getHomeUrl(), "Home URL is required"),
                splitLines(form.getRedirectUrisText()),
                splitLines(form.getPostLogoutRedirectUrisText()),
                form.isEnabled());
    }

    private static List<String> splitLines(String value) {
        if (!StringUtils.hasText(value)) {
            return List.of();
        }
        return value.lines()
                .map(String::trim)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
    }

    private static String normalizeClientId(String value) {
        String normalized = requireText(value, "Client id is required")
                .trim()
                .toLowerCase()
                .replaceAll("[^a-z0-9-]+", "-")
                .replaceAll("(^-+|-+$)", "");
        return requireText(normalized, "Client id is required");
    }

    private static String requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static String valueOrFallback(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private static String generateSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
