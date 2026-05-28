package dev.liangwen.authgateway.platform;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class PersistentPlatformRegistrationRepository implements PlatformRegistrationRepository {

    private final JpaPlatformRegistrationRepository repository;

    PersistentPlatformRegistrationRepository(JpaPlatformRegistrationRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<PlatformRegistration> findById(UUID id) {
        return repository.findById(id).map(PlatformRegistrationEntity::toRegistration);
    }

    @Override
    public Optional<PlatformRegistration> findByClientId(String clientId) {
        return repository.findByClientId(clientId).map(PlatformRegistrationEntity::toRegistration);
    }

    @Override
    public boolean existsByClientId(String clientId) {
        return repository.existsByClientId(clientId);
    }

    @Override
    public List<PlatformRegistration> findAllByOrderByNameAsc() {
        return repository.findAllByOrderByNameAsc().stream()
                .map(PlatformRegistrationEntity::toRegistration)
                .toList();
    }

    @Override
    public List<PlatformRegistration> findAllByEnabledTrueOrderByNameAsc() {
        return repository.findAllByEnabledTrueOrderByNameAsc().stream()
                .map(PlatformRegistrationEntity::toRegistration)
                .toList();
    }

    @Override
    public PlatformRegistration save(PlatformRegistration registration) {
        PlatformRegistrationEntity entity = registration.id() == null
                ? PlatformRegistrationEntity.from(registration)
                : repository.findById(registration.id())
                        .map(existing -> {
                            existing.apply(registration);
                            return existing;
                        })
                        .orElseGet(() -> PlatformRegistrationEntity.from(registration));
        return repository.save(entity).toRegistration();
    }
}
