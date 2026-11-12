package dev.liangwen.authgateway.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "auth-gateway.production-safety.enabled=false",
        "server.address=127.0.0.1",
        "server.servlet.context-path=/gateway",
        "server.forward-headers-strategy=framework",
        "admin.enabled=true",
        "admin.inventory.docker-enabled=false",
        "admin.inventory.systemd-enabled=false",
        "admin.inventory.ports-enabled=false"
})
class AdminForwardingIntegrationTest {

    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void preservesHeaderFreeSshTunnelAccess() throws Exception {
        assertThat(get("/admin/services", Map.of()).statusCode()).isEqualTo(200);
    }

    @Test
    void rejectsForwardingSpoofsThroughTheActualServletFilterChain() throws Exception {
        for (Map<String, String> headers : java.util.List.of(
                Map.of("X-Forwarded-For", "127.0.0.1"),
                Map.of("X-Forwarded-For", "127.0.0.1, 203.0.113.10"),
                Map.of("Forwarded", "for=127.0.0.1;proto=https"),
                Map.of("X-Forwarded-For", "127.0.0.1", "X-Forwarded-Prefix", "/external"))) {
            assertThat(get("/admin/services", headers).statusCode()).isEqualTo(404);
        }
    }

    @Test
    void rejectsEncodedAdminPathAndKeepsPublicHealthReachable() throws Exception {
        assertThat(get("/%61dmin/services", Map.of("X-Forwarded-For", "127.0.0.1")).statusCode())
                .isIn(400, 404);
        HttpResponse<String> health = get("/actuator/health",
                Map.of("X-Forwarded-For", "203.0.113.10", "X-Forwarded-Proto", "https"));
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("UP");
    }

    private HttpResponse<String> get(String path, Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/gateway" + path))
                .timeout(Duration.ofSeconds(10)).GET();
        headers.forEach(request::header);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
