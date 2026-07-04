package dev.liangwen.authgateway.ops;

import static org.assertj.core.api.Assertions.assertThat;

import dev.liangwen.authgateway.admin.AdminProperties;
import dev.liangwen.authgateway.config.IdentityProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class RuntimeSummaryServiceTest {

    @TempDir
    private Path tempDir;

    @Test
    void summarizesSafeRuntimeFacts() throws Exception {
        Path deployInfo = tempDir.resolve("deploy-info.env");
        Files.writeString(deployInfo, """
                APP_NAME=auth-gateway
                REVISION=abc123
                GITHUB_RUN_ID=456
                DEPLOYED_AT=2026-07-05T01:02:03Z
                GOOGLE_CLIENT_SECRET=must-not-leak
                DATABASE_PASSWORD=must-not-leak
                """);

        RuntimeSummaryService service = new RuntimeSummaryService(
                new RuntimeSummaryProperties(deployInfo),
                identity(),
                admin(),
                env("jdbc:postgresql://127.0.0.1:5432/auth_gateway"));

        Map<String, Object> summary = service.summary();

        assertThat(summary)
                .containsEntry("issuer", "https://auth.example.com")
                .containsEntry("database", "postgresql")
                .containsEntry("adminEnabled", true)
                .containsEntry("allowlistConfigured", true)
                .containsEntry("signingKeyConfigured", true)
                .containsEntry("revision", "abc123")
                .containsEntry("githubRunId", "456")
                .containsEntry("deployedAt", "2026-07-05T01:02:03Z");
        assertThat(summary.toString()).doesNotContain("must-not-leak");
    }

    @Test
    void reportsH2DatabaseKindWithoutPrintingRawUrl() {
        RuntimeSummaryService service = new RuntimeSummaryService(
                new RuntimeSummaryProperties(tempDir.resolve("missing.env")),
                identity(),
                admin(),
                env("jdbc:h2:file:/opt/auth-gateway/data/auth-gateway"));

        Map<String, Object> summary = service.summary();

        assertThat(summary).containsEntry("database", "h2");
        assertThat(summary.toString()).doesNotContain("/opt/auth-gateway/data/auth-gateway");
    }

    private static IdentityProperties identity() {
        return new IdentityProperties(
                "https://auth.example.com",
                List.of(),
                List.of(),
                new IdentityProperties.Access(List.of("owner@example.com"), List.of(), true),
                new IdentityProperties.SigningKey("/etc/auth-gateway/auth-gateway-private-key.pem", null, "main"));
    }

    private static AdminProperties admin() {
        return new AdminProperties(
                true,
                new AdminProperties.Inventory(true, true, true, List.of()),
                new AdminProperties.Access(List.of(), null));
    }

    private static MockEnvironment env(String databaseUrl) {
        return new MockEnvironment()
                .withProperty("spring.datasource.url", databaseUrl);
    }
}
