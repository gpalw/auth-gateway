package dev.liangwen.authgateway.platform;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface JpaPlatformRegistrationRepository extends JpaRepository<PlatformRegistrationEntity, UUID> {

    Optional<PlatformRegistrationEntity> findByClientId(String clientId);

    boolean existsByClientId(String clientId);

    List<PlatformRegistrationEntity> findAllByOrderByNameAsc();

    List<PlatformRegistrationEntity> findAllByEnabledTrueOrderByNameAsc();
}
