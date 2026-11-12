package dev.liangwen.authgateway.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.liangwen.authgateway.config.IdentityProperties;
import dev.liangwen.authgateway.config.ProductionSafetyGuard;
import java.util.List;
import org.junit.jupiter.api.Test;

class PersistentPlatformRegistrationRepositoryTest {

    private final JpaPlatformRegistrationRepository database = mock(JpaPlatformRegistrationRepository.class);

    @Test
    void rejectsAlreadyPersistedEnabledDefaultsAtStartupWithoutChangingThem() {
        when(database.findAllByEnabledTrueOrderByNameAsc())
                .thenReturn(List.of(PlatformRegistrationEntity.from(platform("dev-job-crm-secret", true))));
        assertThatThrownBy(() -> repository(true).validatePersistedClients())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Enabled production client 'job-crm-local'");
        verify(database, never()).save(any());
    }

    @Test
    void rejectsWritesBeforeMutatingTheDatabase() {
        assertThatThrownBy(() -> repository(true).save(platform("replace-with-strong-job-crm-secret", true)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(database, never()).save(any());
    }

    @Test
    void permitsDisablingAPlaceholderWithoutRotatingItsSecret() {
        when(database.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        PlatformRegistration saved = repository(true).save(platform("dev-job-crm-secret", false));
        assertThat(saved.enabled()).isFalse();
        assertThat(saved.clientSecret()).isEqualTo("dev-job-crm-secret");
    }

    @Test
    void keepsLocalStartupCompatible() {
        when(database.findAllByEnabledTrueOrderByNameAsc())
                .thenReturn(List.of(PlatformRegistrationEntity.from(platform("dev-job-crm-secret", true))));
        assertThatCode(() -> repository(false).validatePersistedClients()).doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingProductionSeedBeforeSavingItsDefault() {
        PlatformRegistrationService service = new PlatformRegistrationService(repository(true), seedProperties());
        assertThatThrownBy(service::seedConfiguredPlatforms).isInstanceOf(IllegalArgumentException.class);
        verify(database, never()).save(any());
    }

    @Test
    void ignoresUnusedDefaultSeedWhenValidStoredRegistrationAlreadyExists() {
        when(database.findAllByEnabledTrueOrderByNameAsc())
                .thenReturn(List.of(PlatformRegistrationEntity.from(platform("existing-production-value", true))));
        when(database.existsByClientId("job-crm-local")).thenReturn(true);
        PersistentPlatformRegistrationRepository repository = repository(true);
        repository.validatePersistedClients();
        new PlatformRegistrationService(repository, seedProperties()).seedConfiguredPlatforms();
        verify(database, never()).save(any());
    }

    private PersistentPlatformRegistrationRepository repository(boolean production) {
        ProductionSafetyGuard guard = mock(ProductionSafetyGuard.class);
        when(guard.isEnforced()).thenReturn(production);
        return new PersistentPlatformRegistrationRepository(database, new ProductionClientSecretPolicy(guard));
    }

    private static PlatformRegistration platform(String secret, boolean enabled) {
        return new PlatformRegistration(null, "job-crm-local", secret, "Job CRM", "", "https://jobs.example.com",
                List.of("https://jobs.example.com/callback"), List.of(), enabled);
    }

    private static IdentityProperties seedProperties() {
        return new IdentityProperties("https://auth.example.com",
                List.of(new IdentityProperties.App("job-crm-local", "Job CRM", "", "https://jobs.example.com", true)),
                List.of(new IdentityProperties.Client("job-crm-local", "dev-job-crm-secret",
                        List.of("https://jobs.example.com/callback"), List.of())), null, null);
    }
}
