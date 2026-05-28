package dev.liangwen.authgateway.platform;

import static org.assertj.core.api.Assertions.assertThat;

import dev.liangwen.authgateway.config.IdentityProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlatformRegistrationServiceTest {

    private final PlatformRegistrationRepository registrations = new InMemoryPlatformRegistrationRepository();

    @Test
    void seedsMissingPlatformsFromIdentityProperties() {
        IdentityProperties identity = new IdentityProperties(
                "https://auth.example.com",
                List.of(new IdentityProperties.App(
                        "job-crm",
                        "Job CRM",
                        "Application pipeline",
                        "https://tools.example.com/job/",
                        true)),
                List.of(new IdentityProperties.Client(
                        "job-crm",
                        "secret",
                        List.of("https://tools.example.com/job/login/oauth2/code/auth-gateway"),
                        List.of("https://tools.example.com/job/"))),
                null,
                null);
        PlatformRegistrationService service = new PlatformRegistrationService(registrations, identity);

        service.seedConfiguredPlatforms();

        PlatformRegistration platform = registrations.findByClientId("job-crm").orElseThrow();
        assertThat(platform.name()).isEqualTo("Job CRM");
        assertThat(platform.homeUrl()).isEqualTo("https://tools.example.com/job/");
        assertThat(platform.redirectUris())
                .containsExactly("https://tools.example.com/job/login/oauth2/code/auth-gateway");
        assertThat(service.enabledPlatforms()).extracting(PlatformRegistration::clientId).containsExactly("job-crm");
    }

    @Test
    void createsPlatformWithGeneratedSecretWhenBlank() {
        PlatformRegistrationService service = new PlatformRegistrationService(registrations, new IdentityProperties(
                "https://auth.example.com",
                List.of(),
                List.of(),
                null,
                null));

        PlatformRegistration platform = service.create(new PlatformRegistrationForm(
                "billing",
                "Online Billing",
                "Money tools",
                "https://tools.example.com/billing/",
                "",
                "https://tools.example.com/billing/login/oauth2/code/auth-gateway",
                "https://tools.example.com/billing/",
                true));

        assertThat(platform.clientId()).isEqualTo("billing");
        assertThat(platform.clientSecret()).hasSizeGreaterThan(24);
        assertThat(platform.redirectUris())
                .containsExactly("https://tools.example.com/billing/login/oauth2/code/auth-gateway");
    }
}
