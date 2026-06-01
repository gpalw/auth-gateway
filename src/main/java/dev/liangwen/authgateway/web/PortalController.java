package dev.liangwen.authgateway.web;

import dev.liangwen.authgateway.platform.PlatformRegistrationService;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PortalController {

    private final PlatformRegistrationService platforms;

    public PortalController(PlatformRegistrationService platforms) {
        this.platforms = platforms;
    }

    @GetMapping("/")
    String portal(@AuthenticationPrincipal OidcUser principal, Model model) {
        List<AppRegistration> apps = platforms.enabledPlatforms().stream()
                .map(AppRegistration::from)
                .toList();
        Map<String, Object> claims = principal.getClaims();

        model.addAttribute("apps", apps);
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
}
