package dev.liangwen.authgateway.admin;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@ConditionalOnProperty(prefix = "admin.inventory", name = "systemd-enabled", havingValue = "true", matchIfMissing = true)
public class SystemdServiceCollector implements ServiceCollector {

    private final CommandRunner commands;

    public SystemdServiceCollector(CommandRunner commands) {
        this.commands = commands;
    }

    @Override
    public List<CollectedService> collect() {
        CommandResult result = commands.run(List.of(
                "systemctl",
                "list-units",
                "--type=service",
                "--state=running",
                "--no-legend",
                "--no-pager"));
        if (result.exitCode() != 0) {
            throw new IllegalStateException("systemctl list-units failed: " + result.stderr());
        }
        List<CollectedService> services = new ArrayList<>();
        for (String line : result.stdout().lines().toList()) {
            parseLine(line).ifPresent(services::add);
        }
        return services;
    }

    static Optional<CollectedService> parseLine(String line) {
        if (!StringUtils.hasText(line)) {
            return Optional.empty();
        }
        String[] parts = line.trim().split("\\s+", 5);
        if (parts.length < 4) {
            return Optional.empty();
        }
        String description = parts.length == 5 ? parts[4] : "";
        ServiceStatus status = "running".equalsIgnoreCase(parts[3]) ? ServiceStatus.RUNNING : ServiceStatus.UNKNOWN;
        return Optional.of(new CollectedService(
                ServiceSource.SYSTEMD,
                parts[0],
                status,
                "",
                null,
                description,
                line));
    }
}
