package dev.liangwen.authgateway.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class DockerServiceCollectorTest {

    @Test
    void parsesDockerPsOutputIntoServices() {
        CommandRunner runner = command -> new CommandResult(0, """
                auth-gateway\tUp 2 hours\t127.0.0.1:8080->8080/tcp\tauth-gateway:latest
                postgres\tUp 2 hours\t5432/tcp\tpostgres:16-alpine
                """, "");

        DockerServiceCollector collector = new DockerServiceCollector(runner);

        List<CollectedService> services = collector.collect();

        assertThat(services).hasSize(2);
        assertThat(services.getFirst().source()).isEqualTo(ServiceSource.DOCKER);
        assertThat(services.getFirst().name()).isEqualTo("auth-gateway");
        assertThat(services.getFirst().listenAddress()).isEqualTo("127.0.0.1");
        assertThat(services.getFirst().port()).isEqualTo(8080);
        assertThat(services.getFirst().description()).isEqualTo("auth-gateway:latest");
        assertThat(services.get(1).port()).isEqualTo(5432);
    }
}
