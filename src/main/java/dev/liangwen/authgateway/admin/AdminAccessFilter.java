package dev.liangwen.authgateway.admin;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

public class AdminAccessFilter extends OncePerRequestFilter {

    private static final Set<String> LOOPBACK_ADDRESSES = Set.of("127.0.0.1", "0:0:0:0:0:0:0:1", "::1");

    private final AdminProperties properties;

    public AdminAccessFilter(AdminProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (!isAdminPath(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        if (isAllowed(request)) {
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
        return isLoopback(clientAddress) || access.allowedProxyIps().contains(clientAddress);
    }

    private static boolean isAdminPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "/admin".equals(path) || path.startsWith("/admin/");
    }

    private static String effectiveClientAddress(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwardedFor)) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
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
