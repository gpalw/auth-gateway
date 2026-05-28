package dev.liangwen.authgateway.platform;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class InMemoryPlatformRegistrationRepository implements PlatformRegistrationRepository {

    private final Map<UUID, PlatformRegistration> registrations = new LinkedHashMap<>();

    @Override
    public Optional<PlatformRegistration> findById(UUID id) {
        return Optional.ofNullable(registrations.get(id));
    }

    @Override
    public Optional<PlatformRegistration> findByClientId(String clientId) {
        return registrations.values().stream()
                .filter(registration -> registration.clientId().equals(clientId))
                .findFirst();
    }

    @Override
    public boolean existsByClientId(String clientId) {
        return findByClientId(clientId).isPresent();
    }

    @Override
    public List<PlatformRegistration> findAllByOrderByNameAsc() {
        return registrations.values().stream()
                .sorted(Comparator.comparing(PlatformRegistration::name))
                .toList();
    }

    @Override
    public List<PlatformRegistration> findAllByEnabledTrueOrderByNameAsc() {
        return registrations.values().stream()
                .filter(PlatformRegistration::enabled)
                .sorted(Comparator.comparing(PlatformRegistration::name))
                .toList();
    }

    @Override
    public PlatformRegistration save(PlatformRegistration registration) {
        PlatformRegistration saved = registration.id() == null
                ? new PlatformRegistration(
                        UUID.randomUUID(),
                        registration.clientId(),
                        registration.clientSecret(),
                        registration.name(),
                        registration.description(),
                        registration.homeUrl(),
                        registration.redirectUris(),
                        registration.postLogoutRedirectUris(),
                        registration.enabled())
                : registration;
        registrations.put(saved.id(), saved);
        return saved;
    }
}
