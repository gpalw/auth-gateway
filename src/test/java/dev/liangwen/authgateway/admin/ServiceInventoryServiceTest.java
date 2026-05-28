package dev.liangwen.authgateway.admin;

import static org.assertj.core.api.Assertions.assertThat;

import dev.liangwen.authgateway.config.IdentityProperties;
import dev.liangwen.authgateway.platform.InMemoryPlatformRegistrationRepository;
import dev.liangwen.authgateway.platform.PlatformRegistrationService;
import java.util.List;
import org.junit.jupiter.api.Test;

class ServiceInventoryServiceTest {

    @Test
    void combinesConfiguredSsoAppsManualServicesAndRuntimePorts() {
        IdentityProperties identity = new IdentityProperties(
                "https://auth.example.com",
                List.of(new IdentityProperties.App(
                        "job-crm-local",
                        "Job CRM",
                        "Jobs",
                        "https://jobs.example.com",
                        true)),
                List.of(new IdentityProperties.Client(
                        "job-crm-local",
                        "secret",
                        List.of("https://jobs.example.com/login/oauth2/code/auth-gateway"),
                        List.of("https://jobs.example.com"))),
                null,
                null);
        PlatformRegistrationService platforms = new PlatformRegistrationService(
                new InMemoryPlatformRegistrationRepository(),
                identity);
        platforms.seedConfiguredPlatforms();
        AdminProperties admin = new AdminProperties(true, new AdminProperties.Inventory(
                false,
                false,
                true,
                List.of(new AdminProperties.ManualService(
                        "cv-home",
                        "CV Home",
                        "https://liangwendev.com",
                        "443",
                        "public homepage"))));
        ServiceCollector runtimePorts = () -> List.of(new CollectedService(
                ServiceSource.PORT,
                "node",
                ServiceStatus.RUNNING,
                "0.0.0.0",
                3000,
                "users:((\"node\",pid=77,fd=1))",
                "LISTEN ..."));

        ServiceInventoryService service = new ServiceInventoryService(
                platforms,
                admin,
                List.of(runtimePorts));

        ServiceInventory inventory = service.inventory();

        assertThat(inventory.items()).extracting(ServiceInventoryItem::name)
                .contains("Job CRM", "CV Home", "node");
        ServiceInventoryItem sso = inventory.items().stream()
                .filter(item -> item.name().equals("Job CRM"))
                .findFirst()
                .orElseThrow();
        assertThat(sso.authType()).isEqualTo(AuthType.SSO);
        assertThat(sso.oidcClientId()).isEqualTo("job-crm-local");
        assertThat(sso.redirectUris()).containsExactly("https://jobs.example.com/login/oauth2/code/auth-gateway");
        assertThat(inventory.errors()).isEmpty();
    }
}
