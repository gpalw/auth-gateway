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
@ConditionalOnProperty(prefix = "admin.inventory", name = "docker-enabled", havingValue = "true", matchIfMissing = true)
public class DockerServiceCollector implements ServiceCollector {

    private static final Pattern HOST_MAPPING = Pattern.compile("(?:(?<host>[^,\\s:]+):)?(?<port>\\d+)->");
    private static final Pattern EXPOSED_PORT = Pattern.compile("(?<port>\\d+)/(?:tcp|udp)");

    private final CommandRunner commands;

    public DockerServiceCollector(CommandRunner commands) {
        this.commands = commands;
    }

    @Override
    public List<CollectedService> collect() {
        CommandResult result = commands.run(List.of(
                "docker",
                "ps",
                "--format",
                "{{.Names}}\t{{.Status}}\t{{.Ports}}\t{{.Image}}"));
        if (result.exitCode() != 0) {
            throw new IllegalStateException("docker ps failed: " + result.stderr());
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
        String[] columns = line.split("\t", -1);
        if (columns.length < 4) {
            return Optional.empty();
        }
        PortBinding port = parsePort(columns[2]);
        return Optional.of(new CollectedService(
                ServiceSource.DOCKER,
                columns[0],
                columns[1].startsWith("Up") ? ServiceStatus.RUNNING : ServiceStatus.UNKNOWN,
                port.host(),
                port.port(),
                columns[3],
                line));
    }

    private static PortBinding parsePort(String ports) {
        Matcher mapping = HOST_MAPPING.matcher(ports);
        if (mapping.find()) {
            String host = mapping.group("host");
            return new PortBinding(StringUtils.hasText(host) ? host : "0.0.0.0", Integer.parseInt(mapping.group("port")));
        }
        Matcher exposed = EXPOSED_PORT.matcher(ports);
        if (exposed.find()) {
            return new PortBinding("", Integer.parseInt(exposed.group("port")));
        }
        return new PortBinding("", null);
    }

    private record PortBinding(String host, Integer port) {
    }
}
