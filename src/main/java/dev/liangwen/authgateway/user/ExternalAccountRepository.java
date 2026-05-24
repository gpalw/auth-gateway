package dev.liangwen.authgateway.user;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExternalAccountRepository extends JpaRepository<ExternalAccount, UUID> {

    Optional<ExternalAccount> findByProviderAndProviderSubject(String provider, String providerSubject);
}
