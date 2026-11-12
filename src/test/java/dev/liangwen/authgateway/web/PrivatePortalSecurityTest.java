package dev.liangwen.authgateway.web;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties={"auth-gateway.production-safety.enabled=false","private-portal.enabled=true"})
@AutoConfigureMockMvc
class PrivatePortalSecurityTest {
 @Autowired MockMvc mvc;
 @Test void ownerOnlyEvenForAnExistingAuthenticatedSession() throws Exception {
   mvc.perform(get("/api/access/probe").with(oidcLogin().clientRegistration(registration())
      .idToken(t->t.claim("email","lwxyq084891@gmail.com").claim("email_verified",true)))).andExpect(status().isNoContent());
   mvc.perform(get("/api/access/probe").with(oidcLogin().clientRegistration(registration())
      .idToken(t->t.claim("email","other@example.test").claim("email_verified",true)))).andExpect(status().isForbidden());
   mvc.perform(get("/api/access/probe").with(oidcLogin().clientRegistration(registration())
      .idToken(t->t.claim("email","lwxyq084891@gmail.com").claim("email_verified",false)))).andExpect(status().isForbidden());
 }
 @Test void unconfiguredProviderCannotReuseGoogleEmailClaim() throws Exception {
   mvc.perform(get("/api/access/probe").with(oidcLogin()
      .idToken(t->t.claim("email","lwxyq084891@gmail.com").claim("email_verified",true)))).andExpect(status().isForbidden());
 }
 @Test void noIdentityHeaderOrBearerShortcut() throws Exception {
   mvc.perform(get("/api/access/probe").header("X-Auth-Request-Email","lwxyq084891@gmail.com")
      .header("Authorization","Bearer invalid")).andExpect(status().isUnauthorized());
 }
 @Test void returnUrlBoundaries() {
   assertThat(PrivateAppController.safeTarget("/apps/probe/report","view=week&sort=date")).isTrue();
   for(String path:new String[]{"https://evil.test","//evil.test","/apps/probe//evil","/apps/probe/../evil","/apps/probe/%2e%2e/evil","/apps/probe/\\evil"})
     assertThat(PrivateAppController.safeTarget(path,null)).as(path).isFalse();
   assertThat(PrivateAppController.safeTarget("/apps/probe/","x=%0d%0aLocation")).isFalse();
 }
 static org.springframework.security.oauth2.client.registration.ClientRegistration registration(){
  return org.springframework.security.oauth2.client.registration.ClientRegistration.withRegistrationId("google")
   .clientId("fixture").authorizationGrantType(org.springframework.security.oauth2.core.AuthorizationGrantType.AUTHORIZATION_CODE)
   .redirectUri("http://localhost/callback").authorizationUri("http://localhost/authorize").tokenUri("http://localhost/token").build();
 }
}
