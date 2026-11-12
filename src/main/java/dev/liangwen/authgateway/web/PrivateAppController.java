package dev.liangwen.authgateway.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.server.ResponseStatusException;

@Controller
@ConditionalOnProperty(name = "private-portal.enabled", havingValue = "true")
public class PrivateAppController {
    @GetMapping("/api/access/probe")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void checkAccess() {}

    // Spring Security saves the original request, including the query, before login.
    @GetMapping("/sso/start/apps/probe/**")
    void resume(HttpServletRequest request, HttpServletResponse response) {
        String path = request.getRequestURI().substring("/sso/start".length());
        String query = request.getQueryString();
        if (!safeTarget(path, query)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid application path");
        }
        response.setStatus(302);
        response.setHeader("Location", path + (query == null ? "" : "?" + query));
    }
    static boolean safeTarget(String path, String query) {
        return path.startsWith("/apps/probe/")
                && path.matches("/apps/probe/[a-zA-Z0-9/_-]*")
                && !path.contains("//")
                && (query == null || (query.matches("[a-zA-Z0-9%&=+_,.~-]*")
                        && !query.toLowerCase(java.util.Locale.ROOT).matches(".*%(0a|0d|5c).*")));
    }
}
