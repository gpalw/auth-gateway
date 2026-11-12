package dev.liangwen.authgateway.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.ui.ExtendedModelMap;

class LoginAvailabilityTest {

    @Test
    void hidesGoogleWhenNoRegistrationExists() {
        assertThat(available(null)).isFalse();
    }

    @Test
    void hidesGoogleForDevelopmentPlaceholders() {
        assertThat(available(google("dev-google-client-id", "dev-google-client-secret"))).isFalse();
        assertThat(available(google("real-client", "dev-google-client-secret"))).isFalse();
        assertThat(available(google("dev-google-client-id", "real-secret"))).isFalse();
    }

    @Test
    void showsGoogleForConfiguredCredentialsWithoutExposingThem() {
        ClientRegistrationRepository repository = mock(ClientRegistrationRepository.class);
        when(repository.findByRegistrationId("google")).thenReturn(google("real-client", "real-secret"));
        ExtendedModelMap model = new ExtendedModelMap();
        assertThat(new LoginController(repository).login(null, model)).isEqualTo("login");
        assertThat(model).containsExactlyInAnyOrderEntriesOf(Map.of("googleAvailable", true));
    }

    private static boolean available(ClientRegistration registration) {
        ClientRegistrationRepository repository = mock(ClientRegistrationRepository.class);
        when(repository.findByRegistrationId("google")).thenReturn(registration);
        ExtendedModelMap model = new ExtendedModelMap();
        new LoginController(repository).login(null, model);
        return (boolean) model.get("googleAvailable");
    }

    private static ClientRegistration google(String id, String secret) {
        return ClientRegistration.withRegistrationId("google")
                .clientId(id).clientSecret(secret)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/google")
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .tokenUri("https://oauth2.googleapis.com/token")
                .build();
    }
}
