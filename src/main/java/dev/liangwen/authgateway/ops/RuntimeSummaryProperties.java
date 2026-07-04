package dev.liangwen.authgateway.ops;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "auth-gateway.runtime-summary")
public record RuntimeSummaryProperties(Path deployInfoLocation) {
}
