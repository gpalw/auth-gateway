package dev.liangwen.authgateway.admin;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.ForwardedHeaderFilter;

class AdminAccessFilterTest {

    @Test
    void allowsLoopbackAdminRequestWithoutToken() throws Exception {
        for (String address : List.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1")) {
            MockHttpServletResponse response = filter(defaultProperties())
                    .filter(request(address, null), new MockHttpServletResponse());
            assertThat(response.getStatus()).isEqualTo(200);
        }
    }

    @Test
    void rejectsForwardedPublicClientEvenWhenProxyRemoteAddressIsLoopback() throws Exception {
        MockHttpServletRequest request = request("127.0.0.1", "203.0.113.10");

        MockHttpServletResponse response = filter(defaultProperties())
                .filter(request, new MockHttpServletResponse());

        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    void allowsConfiguredAdminProxyIp() throws Exception {
        AdminProperties properties = new AdminProperties(
                true,
                new AdminProperties.Inventory(false, false, false, List.of()),
                new AdminProperties.Access(List.of("203.0.113.10"), null));
        MockHttpServletRequest request = request("127.0.0.1", "203.0.113.10");

        MockHttpServletResponse response = filter(properties)
                .filter(request, new MockHttpServletResponse());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void rejectsSpoofedForwardedLoopbackFromRemotePeer() throws Exception {
        for (String address : List.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1")) {
            assertThat(filter(defaultProperties()).filter(request("203.0.113.10", address),
                    new MockHttpServletResponse()).getStatus()).isEqualTo(404);
        }
    }

    @Test
    void rejectsSpoofedFirstHopEvenThroughLocalProxy() throws Exception {
        MockHttpServletRequest request = request("127.0.0.1", "127.0.0.1, 203.0.113.10");
        assertThat(filter(defaultProperties()).filter(request, new MockHttpServletResponse()).getStatus())
                .isEqualTo(404);
    }

    @Test
    void usesOnlyObservedLastHopForExplicitForwardedClientAllowlist() throws Exception {
        AdminProperties properties = new AdminProperties(true,
                new AdminProperties.Inventory(false, false, false, List.of()),
                new AdminProperties.Access(List.of("203.0.113.10"), null));
        assertThat(filter(properties).filter(request("127.0.0.1", "198.51.100.2, 203.0.113.10"),
                new MockHttpServletResponse()).getStatus()).isEqualTo(200);
        assertThat(filter(properties).filter(request("127.0.0.1", "203.0.113.10, 198.51.100.2"),
                new MockHttpServletResponse()).getStatus()).isEqualTo(404);
        assertThat(filter(properties).filter(request("198.51.100.2", "203.0.113.10"),
                new MockHttpServletResponse()).getStatus()).isEqualTo(404);
    }

    @Test
    void rejectsAmbiguousForwardingAndForwardedLoopback() throws Exception {
        for (String value : List.of("127.0.0.1", "::1", "", "203.0.113.10,", ",203.0.113.10")) {
            assertThat(filter(defaultProperties()).filter(request("127.0.0.1", value),
                    new MockHttpServletResponse()).getStatus()).isEqualTo(404);
        }
        MockHttpServletRequest duplicate = request("127.0.0.1", "203.0.113.10");
        duplicate.addHeader("X-Forwarded-For", "127.0.0.1");
        assertThat(filter(defaultProperties()).filter(duplicate, new MockHttpServletResponse()).getStatus())
                .isEqualTo(404);
        for (String header : List.of("Forwarded", "X-Forwarded-Host", "X-Real-IP")) {
            MockHttpServletRequest request = request("127.0.0.1", null);
            request.addHeader(header, "127.0.0.1");
            assertThat(filter(defaultProperties()).filter(request, new MockHttpServletResponse()).getStatus())
                    .isEqualTo(404);
        }
    }

    @Test
    void unwrapsFrameworkForwardingForAddressAndPrefixChecks() throws Exception {
        for (String header : List.of("X-Forwarded-For", "Forwarded")) {
            MockHttpServletRequest request = request("203.0.113.10", null);
            request.addHeader(header, header.equals("Forwarded") ? "for=127.0.0.1;proto=https" : "127.0.0.1");
            request.addHeader("X-Forwarded-Prefix", "/external");
            MockHttpServletResponse response = new MockHttpServletResponse();
            new ForwardedHeaderFilter().doFilter(request, response, (wrapped, target) ->
                    new AdminAccessFilter(defaultProperties()).doFilter(
                            wrapped, target, new MockFilterChain()));
            assertThat(response.getStatus()).isEqualTo(404);
        }
    }

    @Test
    void checksDecodedAdminPathInsideServletContext() throws Exception {
        for (String path : List.of("/gateway/admin", "/gateway/%61dmin/platforms")) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
            request.setContextPath("/gateway");
            request.setRemoteAddr("203.0.113.10");
            assertThat(filter(defaultProperties()).filter(request, new MockHttpServletResponse()).getStatus())
                    .isEqualTo(404);
        }
    }

    @Test
    void preservesForwardingForNonAdminRequests() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/oauth2/authorize");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "203.0.113.10");
        request.addHeader("X-Forwarded-Proto", "https");
        MockHttpServletResponse response = new MockHttpServletResponse();
        new ForwardedHeaderFilter().doFilter(request, response, (wrapped, target) ->
                new AdminAccessFilter(defaultProperties()).doFilter(wrapped, target, (continued, output) -> {
                    assertThat(((HttpServletRequest) continued).getScheme()).isEqualTo("https");
                    assertThat(continued.getRemoteAddr()).isEqualTo("203.0.113.10");
                    ((HttpServletResponse) output).setStatus(204);
                }));
        assertThat(response.getStatus()).isEqualTo(204);
    }

    @Test
    void requiresAdminTokenWhenConfigured() throws Exception {
        AdminProperties properties = new AdminProperties(
                true,
                new AdminProperties.Inventory(false, false, false, List.of()),
                new AdminProperties.Access(List.of(), "secret-admin-token"));

        MockHttpServletResponse missingToken = filter(properties)
                .filter(request("127.0.0.1", null), new MockHttpServletResponse());
        MockHttpServletRequest requestWithToken = request("203.0.113.10", null);
        requestWithToken.addHeader("Authorization", "Bearer secret-admin-token");
        MockHttpServletResponse accepted = filter(properties)
                .filter(requestWithToken, new MockHttpServletResponse());
        MockHttpServletRequest requestWithHeaderToken = request("203.0.113.10", "127.0.0.1");
        requestWithHeaderToken.addHeader("X-Admin-Access-Token", "secret-admin-token");
        MockHttpServletResponse acceptedHeaderToken = filter(properties)
                .filter(requestWithHeaderToken, new MockHttpServletResponse());

        assertThat(missingToken.getStatus()).isEqualTo(404);
        assertThat(accepted.getStatus()).isEqualTo(200);
        assertThat(acceptedHeaderToken.getStatus()).isEqualTo(200);
    }

    @Test
    void ignoresNonAdminPaths() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/me");
        request.setRemoteAddr("203.0.113.10");

        MockHttpServletResponse response = filter(defaultProperties())
                .filter(request, new MockHttpServletResponse());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    private static AdminAccessFilterHarness filter(AdminProperties properties) {
        return new AdminAccessFilterHarness(new AdminAccessFilter(properties));
    }

    private static AdminProperties defaultProperties() {
        return new AdminProperties(
                true,
                new AdminProperties.Inventory(false, false, false, List.of()),
                new AdminProperties.Access(List.of(), null));
    }

    private static MockHttpServletRequest request(String remoteAddress, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/services");
        request.setRemoteAddr(remoteAddress);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        return request;
    }

    private record AdminAccessFilterHarness(AdminAccessFilter filter) {
        MockHttpServletResponse filter(MockHttpServletRequest request, MockHttpServletResponse response)
                throws ServletException, IOException {
            filter.doFilter(request, response, new MockFilterChain());
            return response;
        }
    }
}
