package dev.liangwen.authgateway.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.liangwen.authgateway.config.ProductionSafetyGuard;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

class ProductionClientStartupIntegrationTest {

    @Test
    void refusesStartupWithEnabledLegacyDefaultAlreadyInTheDatabase() {
        ApplicationContextRunner database = database();
        database.run(context -> context.getBean(JpaPlatformRegistrationRepository.class)
                .save(PlatformRegistrationEntity.from(platform("dev-job-crm-secret", true))));

        database.withUserConfiguration(CredentialConfiguration.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .hasStackTraceContaining("Enabled production client 'job-crm-local' requires a non-placeholder client secret");
        });

        database.run(context -> assertThat(context.getBean(JpaPlatformRegistrationRepository.class)
                .findByClientId("job-crm-local").orElseThrow().toRegistration().clientSecret())
                .isEqualTo("dev-job-crm-secret"));
    }

    @Test
    void acceptsValidOrDisabledPersistedClientsWithoutChangingCredentials() {
        for (PlatformRegistration registration : List.of(
                platform("existing-production-value", true), platform("dev-job-crm-secret", false))) {
            ApplicationContextRunner database = database();
            database.run(context -> context.getBean(JpaPlatformRegistrationRepository.class)
                    .save(PlatformRegistrationEntity.from(registration)));
            database.withUserConfiguration(CredentialConfiguration.class).run(context -> {
                assertThat(context).hasNotFailed();
                PlatformRegistration stored = context.getBean(PlatformRegistrationRepository.class)
                        .findByClientId(registration.clientId()).orElseThrow();
                assertThat(stored.clientSecret()).isEqualTo(registration.clientSecret());
                assertThat(stored.enabled()).isEqualTo(registration.enabled());
            });
        }
    }

    private static ApplicationContextRunner database() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                        HibernateJpaAutoConfiguration.class, TransactionAutoConfiguration.class))
                .withUserConfiguration(DatabaseConfiguration.class)
                .withPropertyValues("spring.datasource.url=jdbc:h2:mem:client-startup-" + UUID.randomUUID()
                                + ";DB_CLOSE_DELAY=-1",
                        "spring.datasource.username=sa", "spring.datasource.password=",
                        "spring.jpa.hibernate.ddl-auto=update");
    }

    private static PlatformRegistration platform(String secret, boolean enabled) {
        return new PlatformRegistration(null, "job-crm-local", secret, "Job CRM", "", "https://jobs.example.com",
                List.of("https://jobs.example.com/callback"), List.of(), enabled);
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = PlatformRegistrationEntity.class)
    @EnableJpaRepositories(basePackageClasses = JpaPlatformRegistrationRepository.class)
    static class DatabaseConfiguration {
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Import({PersistentPlatformRegistrationRepository.class, ProductionClientSecretPolicy.class})
    static class CredentialConfiguration {
        @Bean
        ProductionSafetyGuard productionSafetyGuard() {
            ProductionSafetyGuard guard = mock(ProductionSafetyGuard.class);
            when(guard.isEnforced()).thenReturn(true);
            return guard;
        }
    }
}
