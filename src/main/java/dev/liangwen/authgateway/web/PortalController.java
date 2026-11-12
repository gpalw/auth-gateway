package dev.liangwen.authgateway.web;

import dev.liangwen.authgateway.platform.PlatformRegistrationService;
import java.util.List;
import java.util.Map;
import java.net.URI;
import java.util.ArrayList;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PortalController {

    private final PlatformRegistrationService platforms;
    private final PrivatePortalProperties portal;

    public PortalController(PlatformRegistrationService platforms, PrivatePortalProperties portal) {
        this.platforms = platforms;
        this.portal = portal;
    }

    @GetMapping("/")
    String portal(@AuthenticationPrincipal OidcUser principal, Model model,
            jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Content-Security-Policy",
                "default-src 'self'; img-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
        response.setHeader("Referrer-Policy", "no-referrer");
        List<AppRegistration> apps = new ArrayList<>(platforms.enabledPlatforms().stream()
                .map(AppRegistration::from)
                .filter(app -> safeLaunchUrl(app.url()))
                .toList());
        if (portal.enabled()) {
            apps.add(new AppRegistration("probe", "会话检查 · 测试应用", "低风险测试数据，用于验证登录、返回与退出。", "/apps/probe/"));
        }
        Map<String, Object> claims = principal.getClaims();

        model.addAttribute("apps", apps);
        model.addAttribute("categories", portal.categories());
        model.addAttribute("displayName", claim(claims, "name", principal.getName()));
        model.addAttribute("email", claim(claims, "email", ""));
        model.addAttribute("avatarUrl", claim(claims, "picture", ""));
        return "portal";
    }

    @GetMapping("/signed-out")
    String signedOut() {
        return "signed-out";
    }

    private static String claim(Map<String, Object> claims, String key, String fallback) {
        Object value = claims.get(key);
        return value == null ? fallback : value.toString();
    }

    private static boolean safeLaunchUrl(String value) {
        try {
            URI uri = URI.create(value);
            return ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (RuntimeException exception) {
            return false;
        }
    }
}
