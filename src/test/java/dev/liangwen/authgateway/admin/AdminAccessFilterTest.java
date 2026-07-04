package dev.liangwen.authgateway.admin;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AdminAccessFilterTest {

    @Test
    void allowsLoopbackAdminRequestWithoutToken() throws Exception {
        MockHttpServletResponse response = filter(defaultProperties())
                .filter(request("127.0.0.1", null), new MockHttpServletResponse());

        assertThat(response.getStatus()).isEqualTo(200);
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

        assertThat(missingToken.getStatus()).isEqualTo(404);
        assertThat(accepted.getStatus()).isEqualTo(200);
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
