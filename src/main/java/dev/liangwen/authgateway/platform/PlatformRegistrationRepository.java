package dev.liangwen.authgateway.platform;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlatformRegistrationRepository {

    Optional<PlatformRegistration> findById(UUID id);

    Optional<PlatformRegistration> findByClientId(String clientId);

    boolean existsByClientId(String clientId);

    List<PlatformRegistration> findAllByOrderByNameAsc();

    List<PlatformRegistration> findAllByEnabledTrueOrderByNameAsc();

    PlatformRegistration save(PlatformRegistration registration);
}
