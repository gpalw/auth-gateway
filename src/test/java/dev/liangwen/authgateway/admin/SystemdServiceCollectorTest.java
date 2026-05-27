package dev.liangwen.authgateway.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SystemdServiceCollectorTest {

    @Test
    void parsesRunningSystemdUnits() {
        CommandRunner runner = command -> new CommandResult(0, """
                nginx.service loaded active running A high performance web server
                docker.service loaded active running Docker Application Container Engine
                """, "");

        SystemdServiceCollector collector = new SystemdServiceCollector(runner);

        List<CollectedService> services = collector.collect();

        assertThat(services).hasSize(2);
        assertThat(services.getFirst().source()).isEqualTo(ServiceSource.SYSTEMD);
        assertThat(services.getFirst().name()).isEqualTo("nginx.service");
        assertThat(services.getFirst().status()).isEqualTo(ServiceStatus.RUNNING);
        assertThat(services.getFirst().description()).isEqualTo("A high performance web server");
    }
}
