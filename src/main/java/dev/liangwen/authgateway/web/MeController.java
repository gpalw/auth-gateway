package dev.liangwen.authgateway.web;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class MeController {

    @GetMapping("/api/me")
    CurrentUserResponse me(@AuthenticationPrincipal OidcUser principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return CurrentUserResponse.from(principal);
    }

    record CurrentUserResponse(String userId, String email, String displayName, String avatarUrl) {

        static CurrentUserResponse from(OidcUser principal) {
            Map<String, Object> claims = principal.getClaims();
            String userId = claim(claims, "user_id", principal.getName());
            return new CurrentUserResponse(
                    userId,
                    claim(claims, "email", ""),
                    claim(claims, "name", userId),
                    claim(claims, "picture", null));
        }

        private static String claim(Map<String, Object> claims, String key, String fallback) {
            Object value = claims.get(key);
            return value == null ? fallback : value.toString();
        }
    }
}
