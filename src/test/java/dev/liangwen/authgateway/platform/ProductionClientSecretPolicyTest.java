package dev.liangwen.authgateway.platform;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.liangwen.authgateway.config.ProductionSafetyGuard;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProductionClientSecretPolicyTest {

    @Test
    void rejectsBlankDevelopmentAndExampleSecretsWithoutLeakingThem() {
        ProductionClientSecretPolicy policy = policy(true);
        for (String secret : Arrays.asList(null, "", " ", "dev-job-crm-secret", "dev-interview-secret",
                "dev-online-billing-secret", "replace-with-strong-job-crm-secret", "your-client-secret",
                " {noop}{noop}DEV-JOB-CRM-SECRET ")) {
            assertThatThrownBy(() -> policy.validate(platform(secret, true)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Enabled production client 'billing' requires a non-placeholder client secret; "
                            + "update its stored registration or disable it");
        }
    }

    @Test
    void preservesDevelopmentDefaultsAndDisabledRegistrations() {
        assertThatCode(() -> policy(false).validate(platform("dev-job-crm-secret", true)))
                .doesNotThrowAnyException();
        assertThatCode(() -> policy(true).validate(platform("dev-job-crm-secret", false)))
                .doesNotThrowAnyException();
    }

    @Test
    void doesNotImposeUnrelatedLengthRequirementsOrChangeValues() {
        assertThatCode(() -> policy(true).validate(platform("existing-value", true)))
                .doesNotThrowAnyException();
    }

    private static ProductionClientSecretPolicy policy(boolean production) {
        ProductionSafetyGuard guard = mock(ProductionSafetyGuard.class);
        when(guard.isEnforced()).thenReturn(production);
        return new ProductionClientSecretPolicy(guard);
    }

    private static PlatformRegistration platform(String secret, boolean enabled) {
        return new PlatformRegistration(null, "billing", secret, "Billing", "", "https://billing.example.com",
                List.of("https://billing.example.com/callback"), List.of(), enabled);
    }
}
