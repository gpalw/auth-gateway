package dev.liangwen.authgateway.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ListeningPortParserTest {

    @Test
    void parsesIpv4ListeningPortWithProcess() {
        String line = "LISTEN 0 4096 127.0.0.1:8080 0.0.0.0:* users:((\"java\",pid=1234,fd=12))";

        CollectedService service = ListeningPortCollector.parseLine(line).orElseThrow();

        assertThat(service.source()).isEqualTo(ServiceSource.PORT);
        assertThat(service.name()).isEqualTo("java");
        assertThat(service.listenAddress()).isEqualTo("127.0.0.1");
        assertThat(service.port()).isEqualTo(8080);
        assertThat(service.status()).isEqualTo(ServiceStatus.RUNNING);
        assertThat(service.raw()).contains("pid=1234");
    }

    @Test
    void parsesWildcardIpv6ListeningPort() {
        String line = "LISTEN 0 511 [::]:443 [::]:* users:((\"nginx\",pid=99,fd=7))";

        CollectedService service = ListeningPortCollector.parseLine(line).orElseThrow();

        assertThat(service.name()).isEqualTo("nginx");
        assertThat(service.listenAddress()).isEqualTo("[::]");
        assertThat(service.port()).isEqualTo(443);
    }
}
