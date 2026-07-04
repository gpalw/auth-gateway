package dev.liangwen.authgateway.ops;

import dev.liangwen.authgateway.admin.AdminProperties;
import dev.liangwen.authgateway.config.IdentityProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class RuntimeSummaryService {

    private final RuntimeSummaryProperties properties;
    private final IdentityProperties identity;
    private final AdminProperties admin;
    private final Environment environment;

    public RuntimeSummaryService(
            RuntimeSummaryProperties properties,
            IdentityProperties identity,
            AdminProperties admin,
            Environment environment) {
        this.properties = properties;
        this.identity = identity;
        this.admin = admin;
        this.environment = environment;
    }

    public Map<String, Object> summary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("issuer", identity.issuer());
        summary.put("database", databaseKind(environment.getProperty("spring.datasource.url", "")));
        summary.put("adminEnabled", admin.enabled());
        summary.put("allowlistConfigured", identity.access().hasAllowlist());
        summary.put("signingKeyConfigured", identity.signingKey().isConfigured());
        summary.putAll(deployInfo());
        return summary;
    }

    private Map<String, Object> deployInfo() {
        Map<String, Object> values = new LinkedHashMap<>();
        if (properties.deployInfoLocation() == null || !Files.isRegularFile(properties.deployInfoLocation())) {
            return values;
        }
        try {
            for (String line : Files.readAllLines(properties.deployInfoLocation())) {
                int separator = line.indexOf('=');
                if (separator < 1) {
                    continue;
                }
                String key = line.substring(0, separator).trim();
                String value = line.substring(separator + 1).trim();
                switch (key) {
                    case "REVISION" -> values.put("revision", value);
                    case "GITHUB_RUN_ID" -> values.put("githubRunId", value);
                    case "DEPLOYED_AT" -> values.put("deployedAt", value);
                    case "APP_NAME" -> values.put("appName", value);
                    default -> {
                    }
                }
            }
        } catch (IOException ex) {
            values.put("deployInfoReadable", false);
        }
        return values;
    }

    private static String databaseKind(String databaseUrl) {
        if (!StringUtils.hasText(databaseUrl)) {
            return "unknown";
        }
        String normalized = databaseUrl.trim().toLowerCase();
        if (normalized.startsWith("jdbc:postgresql:")) {
            return "postgresql";
        }
        if (normalized.startsWith("jdbc:h2:")) {
            return "h2";
        }
        return "other";
    }
}
