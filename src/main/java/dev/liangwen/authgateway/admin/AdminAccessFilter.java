package dev.liangwen.authgateway.admin;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.springframework.http.server.RequestPath;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

public class AdminAccessFilter extends OncePerRequestFilter {

    private static final Set<String> LOOPBACK_ADDRESSES = Set.of("127.0.0.1", "0:0:0:0:0:0:0:1", "::1");
    private static final PathPattern ADMIN_PATH = PathPatternParser.defaultInstance.parse("/admin/**");

    private final AdminProperties properties;

    public AdminAccessFilter(AdminProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        // Framework forwarding wraps the servlet request and rewrites its address and path.
        // Authorize against the original transport request, not the forwarded identity.
        HttpServletRequest transportRequest = originalRequest(request);
        if (!isAdminPath(transportRequest)) {
            filterChain.doFilter(request, response);
            return;
        }

        if (isAllowed(transportRequest)) {
            filterChain.doFilter(request, response);
            return;
        }

        response.sendError(HttpServletResponse.SC_NOT_FOUND);
    }

    private boolean isAllowed(HttpServletRequest request) {
        AdminProperties.Access access = properties.access();
        if (access.hasAccessToken()) {
            return access.tokenMatches(bearerToken(request))
                    || access.tokenMatches(request.getHeader("X-Admin-Access-Token"));
        }

        String clientAddress = effectiveClientAddress(request);
        return StringUtils.hasText(clientAddress)
                && (isLoopback(clientAddress) || access.allowedProxyIps().contains(clientAddress));
    }

    private static boolean isAdminPath(HttpServletRequest request) {
        return ADMIN_PATH.matches(RequestPath.parse(request.getRequestURI(), request.getContextPath())
                .pathWithinApplication());
    }

    private static String effectiveClientAddress(HttpServletRequest request) {
        boolean forwarded = Collections.list(request.getHeaderNames()).stream()
                .anyMatch(name -> "Forwarded".equalsIgnoreCase(name)
                        || "X-Real-IP".equalsIgnoreCase(name)
                        || name.regionMatches(true, 0, "X-Forwarded-", 0, "X-Forwarded-".length()));
        if (!forwarded) {
            return request.getRemoteAddr();
        }

        // Only the documented local Nginx proxy is trusted to append its observed peer.
        // Never use an attacker-supplied first hop, RFC Forwarded, or forwarded loopback.
        if (!isLoopback(request.getRemoteAddr()) || request.getHeader("Forwarded") != null) {
            return null;
        }
        List<String> values = Collections.list(request.getHeaders("X-Forwarded-For"));
        if (values.size() != 1) {
            return null;
        }
        String[] hops = values.getFirst().split(",", -1);
        for (String hop : hops) {
            if (!StringUtils.hasText(hop)) {
                return null;
            }
        }
        String clientAddress = hops[hops.length - 1].trim();
        return StringUtils.hasText(clientAddress) && !isLoopback(clientAddress) ? clientAddress : null;
    }

    private static HttpServletRequest originalRequest(HttpServletRequest request) {
        HttpServletRequest original = request;
        while (original instanceof HttpServletRequestWrapper wrapper
                && wrapper.getRequest() instanceof HttpServletRequest nested) {
            original = nested;
        }
        return original;
    }

    private static boolean isLoopback(String address) {
        return LOOPBACK_ADDRESSES.contains(address);
    }

    private static String bearerToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (!StringUtils.hasText(authorization) || !authorization.startsWith("Bearer ")) {
            return null;
        }
        return authorization.substring("Bearer ".length()).trim();
    }
}
