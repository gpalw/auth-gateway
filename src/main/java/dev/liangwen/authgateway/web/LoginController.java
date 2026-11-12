package dev.liangwen.authgateway.web;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class LoginController {

    private final ClientRegistrationRepository registrations;

    public LoginController(ClientRegistrationRepository registrations) {
        this.registrations = registrations;
    }

    @GetMapping("/login")
    String login(@AuthenticationPrincipal OidcUser principal, Model model) {
        if (principal != null) {
            return "redirect:/";
        }
        ClientRegistration google = registrations.findByRegistrationId("google");
        boolean configured = google != null
                && hasConfiguredValue(google.getClientId(), "dev-google-client-id")
                && hasConfiguredValue(google.getClientSecret(), "dev-google-client-secret");
        model.addAttribute("googleAvailable", configured);
        return "login";
    }

    private static boolean hasConfiguredValue(String value, String placeholder) {
        return value != null && !value.isBlank() && !placeholder.equals(value);
    }
}
