package dev.liangwen.authgateway.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import dev.liangwen.authgateway.platform.DatabaseRegisteredClientRepository;
import dev.liangwen.authgateway.platform.PlatformRegistrationRepository;
import dev.liangwen.authgateway.user.GatewayOidcUser;
import dev.liangwen.authgateway.user.GatewayUser;
import dev.liangwen.authgateway.user.UserIdentityService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IdentityProperties.class)
public class SecurityConfig {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) throws Exception {
        OAuth2AuthorizationServerConfigurer authorizationServerConfigurer =
                new OAuth2AuthorizationServerConfigurer();

        http
                .securityMatcher(authorizationServerConfigurer.getEndpointsMatcher())
                .with(authorizationServerConfigurer, authorizationServer ->
                        authorizationServer.oidc(Customizer.withDefaults()))
                .addFilterAfter(new AuthorizationEndpointLoginRedirectFilter(), SecurityContextHolderFilter.class)
                .anonymous(anonymous -> anonymous.disable())
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(
                        new LoginUrlAuthenticationEntryPoint("/oauth2/authorization/google")));

        return http.build();
    }

    @Bean
    SecurityFilterChain applicationSecurityFilterChain(
            HttpSecurity http,
            OAuth2UserService<OidcUserRequest, OidcUser> gatewayOidcUserService) throws Exception {
        http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/styles.css", "/admin/**", "/signed-out", "/error")
                        .permitAll()
                        .anyRequest().authenticated())
                .oauth2Login(oauth2 -> oauth2.userInfoEndpoint(userInfo ->
                        userInfo.oidcUserService(gatewayOidcUserService)))
                .exceptionHandling(exceptions -> exceptions
                        .defaultAuthenticationEntryPointFor(
                                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                                PathPatternRequestMatcher.pathPattern("/api/**"))
                        .defaultAuthenticationEntryPointFor(
                                new LoginUrlAuthenticationEntryPoint("/oauth2/authorization/google"),
                                AnyRequestMatcher.INSTANCE))
                .logout(logout -> logout.logoutSuccessUrl("/signed-out"));

        return http.build();
    }

    @Bean
    GoogleAccountAccessPolicy googleAccountAccessPolicy(IdentityProperties properties) {
        return new GoogleAccountAccessPolicy(properties.access());
    }

    @Bean
    OAuth2UserService<OidcUserRequest, OidcUser> gatewayOidcUserService(
            UserIdentityService users,
            GoogleAccountAccessPolicy accessPolicy) {
        OidcUserService delegate = new OidcUserService();
        return request -> {
            OidcUser googleUser = delegate.loadUser(request);
            accessPolicy.assertAllowed(googleUser);
            GatewayUser gatewayUser = users.upsertGoogleUser(
                    googleUser.getSubject(),
                    googleUser.getEmail(),
                    googleUser.getFullName(),
                    googleUser.getPicture());
            return GatewayOidcUser.from(gatewayUser, googleUser);
        };
    }

    @Bean
    RegisteredClientRepository registeredClientRepository(PlatformRegistrationRepository registrations) {
        return new DatabaseRegisteredClientRepository(registrations);
    }

    @Bean
    AuthorizationServerSettings authorizationServerSettings(IdentityProperties properties) {
        return AuthorizationServerSettings.builder()
                .issuer(properties.issuer())
                .build();
    }

    @Bean
    JWKSource<SecurityContext> jwkSource(IdentityProperties properties) {
        JWKSet jwkSet = new JWKSet(SigningKeyLoader.loadOrGenerate(properties.signingKey()));
        return new ImmutableJWKSet<>(jwkSet);
    }

    @Bean
    JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
        return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
    }

    @Bean
    OAuth2TokenCustomizer<JwtEncodingContext> tokenCustomizer() {
        return context -> {
            if (context.getPrincipal().getPrincipal() instanceof GatewayOidcUser principal) {
                GatewayUser user = principal.gatewayUser();
                String userId = user.id().toString();
                context.getClaims()
                        .claim("sub", userId)
                        .claim("user_id", userId)
                        .claim("email", user.email())
                        .claim("name", user.displayName());
                if (user.avatarUrl() != null) {
                    context.getClaims().claim("picture", user.avatarUrl());
                }
            }
        };
    }

    private static final class AuthorizationEndpointLoginRedirectFilter extends OncePerRequestFilter {
        private final RequestCache requestCache = new HttpSessionRequestCache();

        @Override
        protected void doFilterInternal(
                HttpServletRequest request,
                HttpServletResponse response,
                FilterChain filterChain) throws ServletException, IOException {
            if (isAuthorizeRequest(request) && !isAuthenticated()) {
                requestCache.saveRequest(request, response);
                response.sendRedirect(request.getContextPath() + "/oauth2/authorization/google");
                return;
            }
            filterChain.doFilter(request, response);
        }

        private static boolean isAuthorizeRequest(HttpServletRequest request) {
            return "GET".equals(request.getMethod()) && "/oauth2/authorize".equals(request.getRequestURI());
        }

        private static boolean isAuthenticated() {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            return authentication != null
                    && authentication.isAuthenticated()
                    && !(authentication instanceof AnonymousAuthenticationToken);
        }
    }

}
