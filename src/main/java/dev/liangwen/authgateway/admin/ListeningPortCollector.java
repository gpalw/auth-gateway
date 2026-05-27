package dev.liangwen.authgateway.admin;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@ConditionalOnProperty(prefix = "admin.inventory", name = "ports-enabled", havingValue = "true", matchIfMissing = true)
public class ListeningPortCollector implements ServiceCollector {

    private static final Pattern PROCESS_NAME = Pattern.compile("\"(?<name>[^\"]+)\"");

    private final CommandRunner commands;

    public ListeningPortCollector(CommandRunner commands) {
        this.commands = commands;
    }

    @Override
    public List<CollectedService> collect() {
        CommandResult result = commands.run(List.of("ss", "-ltnpH"));
        if (result.exitCode() != 0) {
            throw new IllegalStateException("ss -ltnpH failed: " + result.stderr());
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
        String[] parts = line.trim().split("\\s+");
        if (parts.length < 4 || !"LISTEN".equalsIgnoreCase(parts[0])) {
            return Optional.empty();
        }
        HostPort hostPort = parseHostPort(parts[3]);
        if (hostPort.port() == null) {
            return Optional.empty();
        }
        return Optional.of(new CollectedService(
                ServiceSource.PORT,
                processName(line),
                ServiceStatus.RUNNING,
                hostPort.host(),
                hostPort.port(),
                processDescription(line),
                line));
    }

    private static HostPort parseHostPort(String value) {
        if (value.startsWith("[")) {
            int closing = value.indexOf(']');
            if (closing > -1 && value.length() > closing + 2 && value.charAt(closing + 1) == ':') {
                return new HostPort(value.substring(0, closing + 1), parsePort(value.substring(closing + 2)));
            }
        }
        int separator = value.lastIndexOf(':');
        if (separator < 0) {
            return new HostPort(value, null);
        }
        return new HostPort(value.substring(0, separator), parsePort(value.substring(separator + 1)));
    }

    private static Integer parsePort(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String processName(String line) {
        Matcher matcher = PROCESS_NAME.matcher(line);
        return matcher.find() ? matcher.group("name") : "unknown";
    }

    private static String processDescription(String line) {
        int users = line.indexOf("users:");
        return users > -1 ? line.substring(users) : "";
    }

    private record HostPort(String host, Integer port) {
    }
}
