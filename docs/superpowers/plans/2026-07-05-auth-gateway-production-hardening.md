# Auth Gateway Production Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Harden auth-gateway into a stage-1 personal production identity gateway with fail-closed login access, application-level admin protection, safe runtime metadata, production checks, and a PostgreSQL migration path.

**Architecture:** Keep the current shape: Spring Boot auth-gateway remains the OIDC issuer and identity portal, while downstream platforms keep their own UI and business APIs. Add small, focused production-safety components around existing configuration and security chains instead of changing token claims or downstream platform contracts. Use scripts and docs for VPS operator checks and H2-to-Postgres migration rehearsal.

**Tech Stack:** Java 21, Spring Boot 4.0.6, Spring Security, Spring Authorization Server, JPA/Hibernate, H2 for local tests, PostgreSQL for production, Maven Wrapper, Bash deployment scripts, systemd, Nginx.

---

## File Structure

- `src/main/java/dev/liangwen/authgateway/config/ProductionSafetyProperties.java`: Binds the production guard feature flag.
- `src/main/java/dev/liangwen/authgateway/config/ProductionSafetyConfiguration.java`: Enables production guard configuration properties.
- `src/main/java/dev/liangwen/authgateway/config/ProductionSafetyGuard.java`: Validates unsafe production configuration at startup.
- `src/test/java/dev/liangwen/authgateway/config/ProductionSafetyGuardTest.java`: Unit tests for production mode detection and fail-closed validation.
- `src/main/java/dev/liangwen/authgateway/admin/AdminProperties.java`: Adds admin access settings without changing inventory settings.
- `src/main/java/dev/liangwen/authgateway/admin/AdminAccessFilter.java`: Blocks `/admin/**` unless the effective client is loopback, explicitly allowed, or supplies the configured admin token.
- `src/test/java/dev/liangwen/authgateway/admin/AdminAccessFilterTest.java`: Unit tests for loopback, forwarded public IP, allowed proxy IP, and token access.
- `src/main/java/dev/liangwen/authgateway/config/SecurityConfig.java`: Installs `AdminAccessFilter` before Spring Security authorization and stops relying on Nginx alone.
- `src/main/java/dev/liangwen/authgateway/ops/RuntimeSummaryProperties.java`: Binds the deploy-info file location.
- `src/main/java/dev/liangwen/authgateway/ops/RuntimeSummaryConfiguration.java`: Enables runtime summary configuration properties.
- `src/main/java/dev/liangwen/authgateway/ops/RuntimeSummaryService.java`: Builds a secret-safe runtime summary from config and deploy metadata.
- `src/main/java/dev/liangwen/authgateway/ops/AuthGatewayInfoContributor.java`: Publishes safe facts under `/actuator/info`.
- `src/test/java/dev/liangwen/authgateway/ops/RuntimeSummaryServiceTest.java`: Verifies redaction and database-kind parsing.
- `src/main/resources/application.yml`: Adds production safety defaults, runtime summary defaults, and admin access env bindings.
- `scripts/prod-check.sh`: Operator check script for VPS invariants.
- `src/test/java/dev/liangwen/authgateway/ops/ProdCheckScriptTest.java`: Verifies script syntax on systems with Bash and guards against obvious secret-printing regressions.
- `docs/operations/h2-to-postgres.md`: Rehearsable migration checklist from live H2 to PostgreSQL.
- `scripts/h2-to-postgres-dry-run.sh`: Validates migration prerequisites and creates backups/exports when the operator supplies an H2 jar.
- `.env.production.example`: Aligns Docker production env with Postgres and current tool URLs.
- `deploy/systemd/auth-gateway.env.example`: Aligns systemd production env with port `19090`, loopback binding, Postgres, allowlist, admin access, and current tool URLs.
- `deploy/nginx/auth-gateway.conf`: Aligns proxy port with the systemd service port.
- `README.md`: Documents stage-1 production baseline, SSH-only admin access, runtime checks, and migration workflow.

---

### Task 1: Production Safety Guard

**Files:**
- Create: `src/main/java/dev/liangwen/authgateway/config/ProductionSafetyProperties.java`
- Create: `src/main/java/dev/liangwen/authgateway/config/ProductionSafetyConfiguration.java`
- Create: `src/main/java/dev/liangwen/authgateway/config/ProductionSafetyGuard.java`
- Create: `src/test/java/dev/liangwen/authgateway/config/ProductionSafetyGuardTest.java`
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: Write failing tests for production safety**

Create `src/test/java/dev/liangwen/authgateway/config/ProductionSafetyGuardTest.java`:

```java
package dev.liangwen.authgateway.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProductionSafetyGuardTest {

    @Test
    void failsWithHttpsIssuerAndEmptyAllowlist() {
        IdentityProperties identity = identity(
                "https://auth.example.com",
                new IdentityProperties.Access(List.of(), List.of(), true),
                new IdentityProperties.SigningKey("/etc/auth-gateway/auth-gateway-private-key.pem", null, "main"));

        assertThatThrownBy(() -> new ProductionSafetyGuard(identity, enabledSafety(), prodEnv()).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Production issuer requires ALLOWED_EMAILS or ALLOWED_DOMAINS");
    }

    @Test
    void allowsLocalhostDevelopmentWithEmptyAllowlist() {
        IdentityProperties identity = identity(
                "http://localhost:8080",
                new IdentityProperties.Access(List.of(), List.of(), true),
                new IdentityProperties.SigningKey(null, null, null));

        assertThatCode(() -> new ProductionSafetyGuard(identity, enabledSafety(), localEnv()).validate())
                .doesNotThrowAnyException();
    }

    @Test
    void failsProductionWhenSigningKeyIsMissing() {
        IdentityProperties identity = identity(
                "https://auth.example.com",
                new IdentityProperties.Access(List.of("owner@example.com"), List.of(), true),
                new IdentityProperties.SigningKey(null, null, null));

        assertThatThrownBy(() -> new ProductionSafetyGuard(identity, enabledSafety(), prodEnv()).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Production issuer requires persistent JWT signing key");
    }

    @Test
    void failsProductionWhenSecureCookieIsDisabled() {
        IdentityProperties identity = identity(
                "https://auth.example.com",
                new IdentityProperties.Access(List.of("owner@example.com"), List.of(), true),
                new IdentityProperties.SigningKey("/etc/auth-gateway/auth-gateway-private-key.pem", null, "main"));

        MockEnvironment env = prodEnv()
                .withProperty("server.servlet.session.cookie.secure", "false");

        assertThatThrownBy(() -> new ProductionSafetyGuard(identity, enabledSafety(), env).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Production issuer requires SESSION_COOKIE_SECURE=true");
    }

    @Test
    void failsProductionWhenH2ConsoleIsEnabled() {
        IdentityProperties identity = identity(
                "https://auth.example.com",
                new IdentityProperties.Access(List.of("owner@example.com"), List.of(), true),
                new IdentityProperties.SigningKey("/etc/auth-gateway/auth-gateway-private-key.pem", null, "main"));

        MockEnvironment env = prodEnv()
                .withProperty("spring.h2.console.enabled", "true");

        assertThatThrownBy(() -> new ProductionSafetyGuard(identity, enabledSafety(), env).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Production issuer cannot use the H2 console");
    }

    @Test
    void failsExplicitProductionWhenIssuerIsPlainHttp() {
        IdentityProperties identity = identity(
                "http://auth.example.com",
                new IdentityProperties.Access(List.of("owner@example.com"), List.of(), true),
                new IdentityProperties.SigningKey("/etc/auth-gateway/auth-gateway-private-key.pem", null, "main"));

        MockEnvironment env = prodEnv()
                .withProperty("AUTH_GATEWAY_ENV", "production");

        assertThatThrownBy(() -> new ProductionSafetyGuard(identity, enabledSafety(), env).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Production issuer must use HTTPS and cannot be localhost");
    }

    @Test
    void canBeDisabledForTargetedTests() {
        IdentityProperties identity = identity(
                "https://auth.example.com",
                new IdentityProperties.Access(List.of(), List.of(), true),
                new IdentityProperties.SigningKey(null, null, null));

        assertThatCode(() -> new ProductionSafetyGuard(identity, new ProductionSafetyProperties(false), prodEnv()).validate())
                .doesNotThrowAnyException();
    }

    private static ProductionSafetyProperties enabledSafety() {
        return new ProductionSafetyProperties(true);
    }

    private static MockEnvironment prodEnv() {
        return new MockEnvironment()
                .withProperty("server.servlet.session.cookie.secure", "true")
                .withProperty("spring.h2.console.enabled", "false");
    }

    private static MockEnvironment localEnv() {
        return new MockEnvironment()
                .withProperty("server.servlet.session.cookie.secure", "false")
                .withProperty("spring.h2.console.enabled", "false");
    }

    private static IdentityProperties identity(
            String issuer,
            IdentityProperties.Access access,
            IdentityProperties.SigningKey signingKey) {
        return new IdentityProperties(issuer, List.of(), List.of(), access, signingKey);
    }
}
```

- [ ] **Step 2: Run the failing guard test**

Run:

```powershell
.\mvnw.cmd -Dtest=ProductionSafetyGuardTest test
```

Expected: FAIL because `ProductionSafetyGuard` and `ProductionSafetyProperties` do not exist.

- [ ] **Step 3: Add production safety properties**

Create `src/main/java/dev/liangwen/authgateway/config/ProductionSafetyProperties.java`:

```java
package dev.liangwen.authgateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "auth-gateway.production-safety")
public record ProductionSafetyProperties(Boolean enabled) {

    public ProductionSafetyProperties {
        enabled = enabled == null ? true : enabled;
    }

    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }
}
```

Create `src/main/java/dev/liangwen/authgateway/config/ProductionSafetyConfiguration.java`:

```java
package dev.liangwen.authgateway.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProductionSafetyProperties.class)
class ProductionSafetyConfiguration {
}
```

- [ ] **Step 4: Add the guard implementation**

Create `src/main/java/dev/liangwen/authgateway/config/ProductionSafetyGuard.java`:

```java
package dev.liangwen.authgateway.config;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
class ProductionSafetyGuard {

    private final IdentityProperties identity;
    private final ProductionSafetyProperties safety;
    private final Environment environment;

    ProductionSafetyGuard(
            IdentityProperties identity,
            ProductionSafetyProperties safety,
            Environment environment) {
        this.identity = identity;
        this.safety = safety;
        this.environment = environment;
    }

    @PostConstruct
    void validate() {
        if (!safety.isEnabled() || !isProductionMode()) {
            return;
        }

        List<String> failures = new ArrayList<>();
        if (!identity.access().hasAllowlist()) {
            failures.add("Production issuer requires ALLOWED_EMAILS or ALLOWED_DOMAINS");
        }
        if (!identity.signingKey().isConfigured()) {
            failures.add("Production issuer requires persistent JWT signing key");
        }
        if (!environment.getProperty("server.servlet.session.cookie.secure", Boolean.class, false)) {
            failures.add("Production issuer requires SESSION_COOKIE_SECURE=true");
        }
        if (environment.getProperty("spring.h2.console.enabled", Boolean.class, false)) {
            failures.add("Production issuer cannot use the H2 console");
        }
        if (!isPublicHttpsIssuer(identity.issuer())) {
            failures.add("Production issuer must use HTTPS and cannot be localhost");
        }

        if (!failures.isEmpty()) {
            throw new IllegalStateException("Unsafe auth-gateway production configuration: "
                    + String.join("; ", failures));
        }
    }

    private boolean isProductionMode() {
        return isProductionFlag(environment.getProperty("APP_ENV"))
                || isProductionFlag(environment.getProperty("AUTH_GATEWAY_ENV"))
                || isPublicHttpsIssuer(identity.issuer());
    }

    private static boolean isProductionFlag(String value) {
        return StringUtils.hasText(value) && "production".equalsIgnoreCase(value.trim());
    }

    private static boolean isPublicHttpsIssuer(String issuer) {
        URI uri = parseUri(issuer);
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())) {
            return false;
        }
        String host = uri.getHost();
        return StringUtils.hasText(host) && !isLocalHost(host);
    }

    private static boolean isLocalHost(String host) {
        String normalized = host.trim().toLowerCase();
        return "localhost".equals(normalized)
                || "127.0.0.1".equals(normalized)
                || "::1".equals(normalized)
                || "[::1]".equals(normalized);
    }

    private static URI parseUri(String issuer) {
        if (!StringUtils.hasText(issuer)) {
            return null;
        }
        try {
            return URI.create(issuer.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
```

- [ ] **Step 5: Add the application.yml safety default**

Modify `src/main/resources/application.yml`:

```yaml
auth-gateway:
  production-safety:
    enabled: ${AUTH_GATEWAY_PRODUCTION_SAFETY_ENABLED:true}
```

Place this top-level block after `server:` or before `admin:`. Keep existing `identity` and `admin` blocks unchanged in this task.

- [ ] **Step 6: Run the guard test to verify it passes**

Run:

```powershell
.\mvnw.cmd -Dtest=ProductionSafetyGuardTest test
```

Expected: PASS.

- [ ] **Step 7: Run the current suite and fix test contexts that intentionally use public HTTPS issuers**

Run:

```powershell
.\mvnw.cmd test
```

Expected on the first run: some `@SpringBootTest` classes with `identity.issuer=https://auth.example.com` may fail because the new guard is correctly fail-closed.

For test classes that are not testing production safety, add this property inside the existing `@SpringBootTest(properties = { ... })` list:

```java
"auth-gateway.production-safety.enabled=false",
```

Do not disable the guard in `ProductionSafetyGuardTest`.

- [ ] **Step 8: Commit task 1**

Run:

```powershell
git add src/main/java/dev/liangwen/authgateway/config src/test/java/dev/liangwen/authgateway/config src/main/resources/application.yml
git commit -m "feat: add production safety guard"
```

---

### Task 2: Application-Level Admin Access Protection

**Files:**
- Modify: `src/main/java/dev/liangwen/authgateway/admin/AdminProperties.java`
- Create: `src/main/java/dev/liangwen/authgateway/admin/AdminAccessFilter.java`
- Create: `src/test/java/dev/liangwen/authgateway/admin/AdminAccessFilterTest.java`
- Modify: `src/main/java/dev/liangwen/authgateway/config/SecurityConfig.java`
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: Write failing admin access tests**

Create `src/test/java/dev/liangwen/authgateway/admin/AdminAccessFilterTest.java`:

```java
package dev.liangwen.authgateway.admin;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AdminAccessFilterTest {

    @Test
    void allowsLoopbackAdminRequestWithoutToken() throws Exception {
        MockHttpServletResponse response = filter(defaultProperties())
                .filter(request("127.0.0.1", null), new MockHttpServletResponse());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void rejectsForwardedPublicClientEvenWhenProxyRemoteAddressIsLoopback() throws Exception {
        MockHttpServletRequest request = request("127.0.0.1", "203.0.113.10");

        MockHttpServletResponse response = filter(defaultProperties())
                .filter(request, new MockHttpServletResponse());

        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    void allowsConfiguredAdminProxyIp() throws Exception {
        AdminProperties properties = new AdminProperties(
                true,
                new AdminProperties.Inventory(false, false, false, List.of()),
                new AdminProperties.Access(List.of("203.0.113.10"), null));
        MockHttpServletRequest request = request("127.0.0.1", "203.0.113.10");

        MockHttpServletResponse response = filter(properties)
                .filter(request, new MockHttpServletResponse());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void requiresAdminTokenWhenConfigured() throws Exception {
        AdminProperties properties = new AdminProperties(
                true,
                new AdminProperties.Inventory(false, false, false, List.of()),
                new AdminProperties.Access(List.of(), "secret-admin-token"));

        MockHttpServletResponse missingToken = filter(properties)
                .filter(request("127.0.0.1", null), new MockHttpServletResponse());
        MockHttpServletRequest requestWithToken = request("203.0.113.10", null);
        requestWithToken.addHeader("Authorization", "Bearer secret-admin-token");
        MockHttpServletResponse accepted = filter(properties)
                .filter(requestWithToken, new MockHttpServletResponse());

        assertThat(missingToken.getStatus()).isEqualTo(404);
        assertThat(accepted.getStatus()).isEqualTo(200);
    }

    @Test
    void ignoresNonAdminPaths() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/me");
        request.setRemoteAddr("203.0.113.10");

        MockHttpServletResponse response = filter(defaultProperties())
                .filter(request, new MockHttpServletResponse());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    private static AdminAccessFilterHarness filter(AdminProperties properties) {
        return new AdminAccessFilterHarness(new AdminAccessFilter(properties));
    }

    private static AdminProperties defaultProperties() {
        return new AdminProperties(
                true,
                new AdminProperties.Inventory(false, false, false, List.of()),
                new AdminProperties.Access(List.of(), null));
    }

    private static MockHttpServletRequest request(String remoteAddress, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/services");
        request.setRemoteAddr(remoteAddress);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        return request;
    }

    private record AdminAccessFilterHarness(AdminAccessFilter filter) {
        MockHttpServletResponse filter(MockHttpServletRequest request, MockHttpServletResponse response)
                throws ServletException, IOException {
            filter.doFilter(request, response, new MockFilterChain());
            return response;
        }
    }
}
```

- [ ] **Step 2: Run the failing admin access test**

Run:

```powershell
.\mvnw.cmd -Dtest=AdminAccessFilterTest test
```

Expected: FAIL because `AdminProperties.Access` and `AdminAccessFilter` do not exist.

- [ ] **Step 3: Extend admin properties**

Modify `src/main/java/dev/liangwen/authgateway/admin/AdminProperties.java`:

```java
package dev.liangwen.authgateway.admin;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "admin")
public record AdminProperties(boolean enabled, Inventory inventory, Access access) {

    public AdminProperties {
        inventory = inventory == null ? new Inventory(true, true, true, List.of()) : inventory;
        access = access == null ? new Access(List.of(), null) : access;
    }

    public record Inventory(
            boolean dockerEnabled,
            boolean systemdEnabled,
            boolean portsEnabled,
            List<ManualService> services) {

        public Inventory {
            services = services == null ? List.of() : List.copyOf(services);
        }
    }

    public record Access(List<String> allowedProxyIps, String accessToken) {

        public Access {
            allowedProxyIps = normalize(allowedProxyIps);
            accessToken = StringUtils.hasText(accessToken) ? accessToken.trim() : null;
        }

        public boolean hasAccessToken() {
            return accessToken != null;
        }

        public boolean tokenMatches(String value) {
            return accessToken != null && accessToken.equals(value);
        }

        private static List<String> normalize(List<String> values) {
            if (values == null) {
                return List.of();
            }
            return values.stream()
                    .map(value -> StringUtils.hasText(value) ? value.trim() : null)
                    .filter(StringUtils::hasText)
                    .distinct()
                    .toList();
        }
    }

    public record ManualService(String id, String name, String url, String port, String notes) {
    }
}
```

- [ ] **Step 4: Add the admin access filter**

Create `src/main/java/dev/liangwen/authgateway/admin/AdminAccessFilter.java`:

```java
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
            return access.tokenMatches(bearerToken(request)) || access.tokenMatches(request.getHeader("X-Admin-Access-Token"));
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
```

- [ ] **Step 5: Install the filter in the application security chain**

Modify `src/main/java/dev/liangwen/authgateway/config/SecurityConfig.java`.

Add imports:

```java
import dev.liangwen.authgateway.admin.AdminAccessFilter;
import dev.liangwen.authgateway.admin.AdminProperties;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
```

Change the `applicationSecurityFilterChain` method signature:

```java
SecurityFilterChain applicationSecurityFilterChain(
        HttpSecurity http,
        OAuth2UserService<OidcUserRequest, OidcUser> gatewayOidcUserService,
        AdminProperties adminProperties) throws Exception {
```

Add the filter before `exceptionHandling`:

```java
.addFilterBefore(new AdminAccessFilter(adminProperties), AuthorizationFilter.class)
```

Keep `/admin/**` in `permitAll()` so SSH-tunneled admin pages do not redirect to Google. The new filter is the application-level gate.

- [ ] **Step 6: Add admin access env bindings**

Modify `src/main/resources/application.yml` under `admin:`:

```yaml
  access:
    allowed-proxy-ips: ${ADMIN_ALLOWED_PROXY_IPS:}
    access-token: ${ADMIN_ACCESS_TOKEN:}
```

- [ ] **Step 7: Run admin filter tests**

Run:

```powershell
.\mvnw.cmd -Dtest=AdminAccessFilterTest test
```

Expected: PASS.

- [ ] **Step 8: Update existing admin controller tests only if needed**

Run:

```powershell
.\mvnw.cmd -Dtest=AdminServicesControllerTest,AdminPlatformsControllerTest test
```

Expected: PASS because MockMvc uses loopback by default. If a test fails with 404, add this request customizer to that test request:

```java
.with(request -> {
    request.setRemoteAddr("127.0.0.1");
    return request;
})
```

- [ ] **Step 9: Commit task 2**

Run:

```powershell
git add src/main/java/dev/liangwen/authgateway/admin src/main/java/dev/liangwen/authgateway/config/SecurityConfig.java src/test/java/dev/liangwen/authgateway/admin src/main/resources/application.yml
git commit -m "feat: protect admin paths in application"
```

---

### Task 3: Safe Runtime Summary And Actuator Info

**Files:**
- Create: `src/main/java/dev/liangwen/authgateway/ops/RuntimeSummaryProperties.java`
- Create: `src/main/java/dev/liangwen/authgateway/ops/RuntimeSummaryConfiguration.java`
- Create: `src/main/java/dev/liangwen/authgateway/ops/RuntimeSummaryService.java`
- Create: `src/main/java/dev/liangwen/authgateway/ops/AuthGatewayInfoContributor.java`
- Create: `src/test/java/dev/liangwen/authgateway/ops/RuntimeSummaryServiceTest.java`
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: Write failing runtime summary tests**

Create `src/test/java/dev/liangwen/authgateway/ops/RuntimeSummaryServiceTest.java`:

```java
package dev.liangwen.authgateway.ops;

import static org.assertj.core.api.Assertions.assertThat;

import dev.liangwen.authgateway.admin.AdminProperties;
import dev.liangwen.authgateway.config.IdentityProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class RuntimeSummaryServiceTest {

    @TempDir
    private Path tempDir;

    @Test
    void summarizesSafeRuntimeFacts() throws Exception {
        Path deployInfo = tempDir.resolve("deploy-info.env");
        Files.writeString(deployInfo, """
                APP_NAME=auth-gateway
                REVISION=abc123
                GITHUB_RUN_ID=456
                DEPLOYED_AT=2026-07-05T01:02:03Z
                GOOGLE_CLIENT_SECRET=must-not-leak
                DATABASE_PASSWORD=must-not-leak
                """);

        RuntimeSummaryService service = new RuntimeSummaryService(
                new RuntimeSummaryProperties(deployInfo),
                identity(),
                admin(),
                env("jdbc:postgresql://127.0.0.1:5432/auth_gateway"));

        Map<String, Object> summary = service.summary();

        assertThat(summary)
                .containsEntry("issuer", "https://auth.example.com")
                .containsEntry("database", "postgresql")
                .containsEntry("adminEnabled", true)
                .containsEntry("allowlistConfigured", true)
                .containsEntry("signingKeyConfigured", true)
                .containsEntry("revision", "abc123")
                .containsEntry("githubRunId", "456")
                .containsEntry("deployedAt", "2026-07-05T01:02:03Z");
        assertThat(summary.toString()).doesNotContain("must-not-leak");
    }

    @Test
    void reportsH2DatabaseKindWithoutPrintingRawUrl() {
        RuntimeSummaryService service = new RuntimeSummaryService(
                new RuntimeSummaryProperties(tempDir.resolve("missing.env")),
                identity(),
                admin(),
                env("jdbc:h2:file:/opt/auth-gateway/data/auth-gateway"));

        Map<String, Object> summary = service.summary();

        assertThat(summary).containsEntry("database", "h2");
        assertThat(summary.toString()).doesNotContain("/opt/auth-gateway/data/auth-gateway");
    }

    private static IdentityProperties identity() {
        return new IdentityProperties(
                "https://auth.example.com",
                List.of(),
                List.of(),
                new IdentityProperties.Access(List.of("owner@example.com"), List.of(), true),
                new IdentityProperties.SigningKey("/etc/auth-gateway/auth-gateway-private-key.pem", null, "main"));
    }

    private static AdminProperties admin() {
        return new AdminProperties(
                true,
                new AdminProperties.Inventory(true, true, true, List.of()),
                new AdminProperties.Access(List.of(), null));
    }

    private static MockEnvironment env(String databaseUrl) {
        return new MockEnvironment()
                .withProperty("spring.datasource.url", databaseUrl);
    }
}
```

- [ ] **Step 2: Run the failing runtime summary test**

Run:

```powershell
.\mvnw.cmd -Dtest=RuntimeSummaryServiceTest test
```

Expected: FAIL because the `ops` classes do not exist.

- [ ] **Step 3: Add runtime summary properties**

Create `src/main/java/dev/liangwen/authgateway/ops/RuntimeSummaryProperties.java`:

```java
package dev.liangwen.authgateway.ops;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "auth-gateway.runtime-summary")
public record RuntimeSummaryProperties(Path deployInfoLocation) {
}
```

Create `src/main/java/dev/liangwen/authgateway/ops/RuntimeSummaryConfiguration.java`:

```java
package dev.liangwen.authgateway.ops;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RuntimeSummaryProperties.class)
class RuntimeSummaryConfiguration {
}
```

- [ ] **Step 4: Add the runtime summary service**

Create `src/main/java/dev/liangwen/authgateway/ops/RuntimeSummaryService.java`:

```java
package dev.liangwen.authgateway.ops;

import dev.liangwen.authgateway.admin.AdminProperties;
import dev.liangwen.authgateway.config.IdentityProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class RuntimeSummaryService {

    private final RuntimeSummaryProperties properties;
    private final IdentityProperties identity;
    private final AdminProperties admin;
    private final Environment environment;

    public RuntimeSummaryService(
            RuntimeSummaryProperties properties,
            IdentityProperties identity,
            AdminProperties admin,
            Environment environment) {
        this.properties = properties;
        this.identity = identity;
        this.admin = admin;
        this.environment = environment;
    }

    public Map<String, Object> summary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("issuer", identity.issuer());
        summary.put("database", databaseKind(environment.getProperty("spring.datasource.url", "")));
        summary.put("adminEnabled", admin.enabled());
        summary.put("allowlistConfigured", identity.access().hasAllowlist());
        summary.put("signingKeyConfigured", identity.signingKey().isConfigured());
        summary.putAll(deployInfo());
        return summary;
    }

    private Map<String, Object> deployInfo() {
        Map<String, Object> values = new LinkedHashMap<>();
        if (properties.deployInfoLocation() == null || !Files.isRegularFile(properties.deployInfoLocation())) {
            return values;
        }
        try {
            for (String line : Files.readAllLines(properties.deployInfoLocation())) {
                int separator = line.indexOf('=');
                if (separator < 1) {
                    continue;
                }
                String key = line.substring(0, separator).trim();
                String value = line.substring(separator + 1).trim();
                switch (key) {
                    case "REVISION" -> values.put("revision", value);
                    case "GITHUB_RUN_ID" -> values.put("githubRunId", value);
                    case "DEPLOYED_AT" -> values.put("deployedAt", value);
                    case "APP_NAME" -> values.put("appName", value);
                    default -> {
                    }
                }
            }
        } catch (IOException ex) {
            values.put("deployInfoReadable", false);
        }
        return values;
    }

    private static String databaseKind(String databaseUrl) {
        if (!StringUtils.hasText(databaseUrl)) {
            return "unknown";
        }
        String normalized = databaseUrl.trim().toLowerCase();
        if (normalized.startsWith("jdbc:postgresql:")) {
            return "postgresql";
        }
        if (normalized.startsWith("jdbc:h2:")) {
            return "h2";
        }
        return "other";
    }
}
```

- [ ] **Step 5: Add actuator info contributor**

Create `src/main/java/dev/liangwen/authgateway/ops/AuthGatewayInfoContributor.java`:

```java
package dev.liangwen.authgateway.ops;

import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Component;

@Component
class AuthGatewayInfoContributor implements InfoContributor {

    private final RuntimeSummaryService runtimeSummary;

    AuthGatewayInfoContributor(RuntimeSummaryService runtimeSummary) {
        this.runtimeSummary = runtimeSummary;
    }

    @Override
    public void contribute(Info.Builder builder) {
        builder.withDetail("authGateway", runtimeSummary.summary());
    }
}
```

- [ ] **Step 6: Add runtime summary default location**

Modify `src/main/resources/application.yml` inside the top-level `auth-gateway:` block:

```yaml
  runtime-summary:
    deploy-info-location: ${AUTH_GATEWAY_DEPLOY_INFO_PATH:/opt/auth-gateway/current-release/deploy-info.env}
```

- [ ] **Step 7: Run runtime summary tests**

Run:

```powershell
.\mvnw.cmd -Dtest=RuntimeSummaryServiceTest test
```

Expected: PASS.

- [ ] **Step 8: Run actuator info smoke test manually**

Run:

```powershell
.\mvnw.cmd test
```

Expected: PASS. If an existing context test starts failing because `/actuator/info` now has an additional detail, update assertions to include only existing required fields and not exact full JSON bodies.

- [ ] **Step 9: Commit task 3**

Run:

```powershell
git add src/main/java/dev/liangwen/authgateway/ops src/test/java/dev/liangwen/authgateway/ops src/main/resources/application.yml
git commit -m "feat: expose safe runtime summary"
```

---

### Task 4: VPS Production Check Script

**Files:**
- Create: `scripts/prod-check.sh`
- Create: `src/test/java/dev/liangwen/authgateway/ops/ProdCheckScriptTest.java`

- [ ] **Step 1: Write failing script tests**

Create `src/test/java/dev/liangwen/authgateway/ops/ProdCheckScriptTest.java`:

```java
package dev.liangwen.authgateway.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ProdCheckScriptTest {

    @Test
    void scriptDoesNotContainDirectSecretEchoes() throws Exception {
        String script = Files.readString(Path.of("scripts/prod-check.sh"));

        assertThat(script)
                .doesNotContain("echo \"$GOOGLE_CLIENT_SECRET\"")
                .doesNotContain("echo \"$DATABASE_PASSWORD\"")
                .doesNotContain("echo \"$AUTH_GATEWAY_JWT_PRIVATE_KEY\"")
                .doesNotContain("cat \"$AUTH_GATEWAY_JWT_PRIVATE_KEY_PATH\"");
    }

    @Test
    void scriptHasValidBashSyntaxWhenBashIsAvailable() throws Exception {
        assumeTrue(hasBash());

        Process process = new ProcessBuilder("bash", "-n", "scripts/prod-check.sh")
                .redirectErrorStream(true)
                .start();
        boolean exited = process.waitFor(Duration.ofSeconds(5));

        assertThat(exited).isTrue();
        assertThat(process.exitValue()).isZero();
    }

    private static boolean hasBash() {
        try {
            Process process = new ProcessBuilder("bash", "--version")
                    .redirectErrorStream(true)
                    .start();
            return process.waitFor(Duration.ofSeconds(5)) && process.exitValue() == 0;
        } catch (Exception ex) {
            return false;
        }
    }
}
```

- [ ] **Step 2: Run the failing script test**

Run:

```powershell
.\mvnw.cmd -Dtest=ProdCheckScriptTest test
```

Expected: FAIL because `scripts/prod-check.sh` does not exist.

- [ ] **Step 3: Add prod-check script**

Create `scripts/prod-check.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail

ENV_FILE="${ENV_FILE:-/etc/auth-gateway/auth-gateway.env}"
SERVICE_NAME="${SERVICE_NAME:-auth-gateway.service}"
INSTALL_DIR="${INSTALL_DIR:-/opt/auth-gateway}"
NGINX_CONFIG="${NGINX_CONFIG:-/etc/nginx/sites-enabled/auth.liangwendev.com.conf}"

failures=0

fail() {
  failures=$((failures + 1))
  printf 'FAIL %s\n' "$1"
}

pass() {
  printf 'PASS %s\n' "$1"
}

read_env_value() {
  local key="$1"
  if [ ! -f "$ENV_FILE" ]; then
    return 0
  fi
  awk -F= -v key="$key" '
    $1 == key {
      value = substr($0, length(key) + 2)
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
      gsub(/^"|"$/, "", value)
      gsub(/^'\''|'\''$/, "", value)
      print value
    }
  ' "$ENV_FILE" | tail -n 1
}

check_systemd() {
  if systemctl is-active --quiet "$SERVICE_NAME"; then
    pass "systemd service is active"
  else
    fail "systemd service is not active: $SERVICE_NAME"
  fi
}

check_local_health() {
  local address port url body
  address="$(read_env_value SERVER_ADDRESS)"
  port="$(read_env_value PORT)"
  address="${address:-127.0.0.1}"
  port="${port:-8080}"
  url="http://$address:$port/actuator/health"
  body="$(curl -fsS --max-time 5 "$url" 2>/dev/null || true)"
  if printf '%s' "$body" | grep -q '"status":"UP"'; then
    pass "local health is UP"
  else
    fail "local health is not UP at $url"
  fi
}

check_public_oidc() {
  local issuer metadata jwks admin_status
  issuer="$(read_env_value AUTH_GATEWAY_ISSUER)"
  if [ -z "$issuer" ]; then
    fail "AUTH_GATEWAY_ISSUER is empty"
    return
  fi
  metadata="$(curl -fsS --max-time 8 "$issuer/.well-known/openid-configuration" 2>/dev/null || true)"
  jwks="$(curl -fsS --max-time 8 "$issuer/oauth2/jwks" 2>/dev/null || true)"
  admin_status="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 8 "$issuer/admin/services" 2>/dev/null || true)"
  if printf '%s' "$metadata" | grep -q '"issuer"'; then
    pass "public OIDC metadata loads"
  else
    fail "public OIDC metadata does not load"
  fi
  if printf '%s' "$jwks" | grep -q '"keys"'; then
    pass "public JWKS loads"
  else
    fail "public JWKS does not load"
  fi
  if [ "$admin_status" = "404" ]; then
    pass "public admin path returns 404"
  else
    fail "public admin path returned $admin_status instead of 404"
  fi
}

check_env_invariants() {
  local allowed_emails allowed_domains database_url secure_cookie h2_console key_path
  allowed_emails="$(read_env_value ALLOWED_EMAILS)"
  allowed_domains="$(read_env_value ALLOWED_DOMAINS)"
  database_url="$(read_env_value DATABASE_URL)"
  secure_cookie="$(read_env_value SESSION_COOKIE_SECURE)"
  h2_console="$(read_env_value H2_CONSOLE_ENABLED)"
  key_path="$(read_env_value AUTH_GATEWAY_JWT_PRIVATE_KEY_PATH)"

  if [ -n "$allowed_emails" ] || [ -n "$allowed_domains" ]; then
    pass "Google allowlist is configured"
  else
    fail "Google allowlist is empty"
  fi

  if printf '%s' "$database_url" | grep -q '^jdbc:postgresql:'; then
    pass "database is PostgreSQL"
  else
    fail "database is not PostgreSQL"
  fi

  if [ "$secure_cookie" = "true" ]; then
    pass "secure session cookie is enabled"
  else
    fail "SESSION_COOKIE_SECURE is not true"
  fi

  if [ "$h2_console" = "false" ] || [ -z "$h2_console" ]; then
    pass "H2 console is disabled"
  else
    fail "H2 console is enabled"
  fi

  if [ -n "$key_path" ] && [ -f "$key_path" ]; then
    local mode
    mode="$(stat -c '%a' "$key_path" 2>/dev/null || true)"
    if [ "$mode" = "600" ] || [ "$mode" = "400" ]; then
      pass "JWT signing key file exists with restricted permissions"
    else
      fail "JWT signing key permissions are $mode"
    fi
  else
    fail "JWT signing key file is missing"
  fi
}

check_nginx_admin_block() {
  if [ -f "$NGINX_CONFIG" ] && grep -q 'location \^~ /admin' "$NGINX_CONFIG" && grep -q 'return 404' "$NGINX_CONFIG"; then
    pass "Nginx blocks public admin path"
  else
    fail "Nginx admin 404 block was not found"
  fi
}

check_platform_registration_hint() {
  local interview_url job_url
  interview_url="$(read_env_value INTERVIEW_URL)"
  job_url="$(read_env_value JOB_CRM_URL)"
  if printf '%s' "$interview_url" | grep -q 'tools.liangwendev.com/interview'; then
    pass "Interview launch URL uses tools.liangwendev.com/interview"
  else
    fail "Interview launch URL is not tools.liangwendev.com/interview"
  fi
  if printf '%s' "$job_url" | grep -q 'tools.liangwendev.com/job'; then
    pass "Job CRM launch URL uses tools.liangwendev.com/job"
  else
    fail "Job CRM launch URL is not tools.liangwendev.com/job"
  fi
}

if [ ! -f "$ENV_FILE" ]; then
  fail "env file is missing: $ENV_FILE"
else
  pass "env file exists"
fi

check_systemd
check_local_health
check_public_oidc
check_env_invariants
check_nginx_admin_block
check_platform_registration_hint

if [ "$failures" -eq 0 ]; then
  printf 'Production check passed\n'
  exit 0
fi

printf 'Production check failed with %s issue(s)\n' "$failures"
exit 1
```

- [ ] **Step 4: Run script tests**

Run:

```powershell
.\mvnw.cmd -Dtest=ProdCheckScriptTest test
```

Expected: PASS. On Windows without Bash, the syntax test is skipped and the secret-regression test still runs.

- [ ] **Step 5: Commit task 4**

Run:

```powershell
git add scripts/prod-check.sh src/test/java/dev/liangwen/authgateway/ops/ProdCheckScriptTest.java
git commit -m "feat: add production check script"
```

---

### Task 5: H2 To PostgreSQL Migration Package

**Files:**
- Create: `docs/operations/h2-to-postgres.md`
- Create: `scripts/h2-to-postgres-dry-run.sh`

- [ ] **Step 1: Create migration documentation**

Create `docs/operations/h2-to-postgres.md`:

```markdown
# H2 To PostgreSQL Migration

This migration moves auth-gateway production persistence from the temporary H2 file database to PostgreSQL.

## Tables To Preserve

- `app_users`
- `external_accounts`
- `platform_registrations`
- `platform_redirect_uris`
- `platform_logout_redirect_uris`

## Maintenance Window

1. Announce a short login maintenance window.
2. Stop auth-gateway with `sudo systemctl stop auth-gateway.service`.
3. Back up `/opt/auth-gateway/data/auth-gateway*`.
4. Export H2 SQL.
5. Create PostgreSQL database and user.
6. Import exported data.
7. Update `/etc/auth-gateway/auth-gateway.env`.
8. Start auth-gateway.
9. Run `scripts/prod-check.sh`.

## PostgreSQL Setup

```bash
sudo -u postgres psql <<'SQL'
CREATE USER auth_gateway WITH PASSWORD 'replace-with-strong-password';
CREATE DATABASE auth_gateway OWNER auth_gateway;
SQL
```

## Env Switch

```text
DATABASE_URL=jdbc:postgresql://127.0.0.1:5432/auth_gateway
DATABASE_USERNAME=auth_gateway
DATABASE_PASSWORD=replace-with-strong-password
JPA_DDL_AUTO=update
H2_CONSOLE_ENABLED=false
```

## Verification

```bash
sudo systemctl start auth-gateway.service
curl -fsS http://127.0.0.1:19090/actuator/health
curl -fsS https://auth.liangwendev.com/.well-known/openid-configuration
curl -fsS https://auth.liangwendev.com/oauth2/jwks
sudo /opt/auth-gateway/current-release/scripts/prod-check.sh
```

The gateway user ids must not change. Downstream apps use `sub` and `user_id`, so preserving `app_users.id` is required.
```

- [ ] **Step 2: Add dry-run helper script**

Create `scripts/h2-to-postgres-dry-run.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail

H2_DATABASE_PATH="${H2_DATABASE_PATH:-/opt/auth-gateway/data/auth-gateway}"
BACKUP_DIR="${BACKUP_DIR:-/opt/auth-gateway/backups/h2-to-postgres-$(date -u +%Y%m%dT%H%M%SZ)}"
H2_JAR="${H2_JAR:-}"

echo "H2 database path: $H2_DATABASE_PATH"
echo "Backup directory: $BACKUP_DIR"

if [ "$(id -u)" -ne 0 ]; then
  echo "Run with sudo so the auth-gateway data files can be backed up." >&2
  exit 2
fi

if ! ls "$H2_DATABASE_PATH"* >/dev/null 2>&1; then
  echo "No H2 files found for $H2_DATABASE_PATH" >&2
  exit 2
fi

install -d -m 0700 "$BACKUP_DIR"
cp -a "$H2_DATABASE_PATH"* "$BACKUP_DIR/"
echo "Backed up H2 files to $BACKUP_DIR"

if [ -z "$H2_JAR" ]; then
  echo "Set H2_JAR to an H2 jar path to export SQL with org.h2.tools.Script."
  echo "Dry-run stopped after backup."
  exit 0
fi

if [ ! -f "$H2_JAR" ]; then
  echo "H2_JAR does not exist: $H2_JAR" >&2
  exit 2
fi

java -cp "$H2_JAR" org.h2.tools.Script \
  -url "jdbc:h2:file:$H2_DATABASE_PATH;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE" \
  -user sa \
  -script "$BACKUP_DIR/auth-gateway-h2-export.sql"

echo "Exported H2 SQL to $BACKUP_DIR/auth-gateway-h2-export.sql"
echo "Review the SQL before importing it into PostgreSQL."
```

- [ ] **Step 3: Verify shell syntax**

Run on a machine with Bash:

```bash
bash -n scripts/h2-to-postgres-dry-run.sh
```

Expected: command exits `0`.

- [ ] **Step 4: Commit task 5**

Run:

```powershell
git add docs/operations/h2-to-postgres.md scripts/h2-to-postgres-dry-run.sh
git commit -m "docs: add h2 to postgres migration path"
```

---

### Task 6: Production Env And Documentation Alignment

**Files:**
- Modify: `.env.production.example`
- Modify: `deploy/systemd/auth-gateway.env.example`
- Modify: `deploy/nginx/auth-gateway.conf`
- Modify: `README.md`

- [ ] **Step 1: Update Docker production env example**

Modify `.env.production.example` so it includes explicit database values and current tool URLs:

```text
PORT=8080
AUTH_GATEWAY_ISSUER=https://auth.liangwendev.com
SESSION_COOKIE_SECURE=true
AUTH_GATEWAY_PRODUCTION_SAFETY_ENABLED=true

GOOGLE_CLIENT_ID=replace-with-google-client-id
GOOGLE_CLIENT_SECRET=replace-with-google-client-secret

DATABASE_URL=jdbc:postgresql://postgres:5432/auth_gateway
DATABASE_USERNAME=auth_gateway
DATABASE_PASSWORD=replace-with-strong-postgres-password
POSTGRES_PASSWORD=replace-with-same-strong-postgres-password
JPA_DDL_AUTO=update
H2_CONSOLE_ENABLED=false

ADMIN_ENABLED=true
ADMIN_DOCKER_ENABLED=true
ADMIN_SYSTEMD_ENABLED=true
ADMIN_PORTS_ENABLED=true
ADMIN_ALLOWED_PROXY_IPS=
ADMIN_ACCESS_TOKEN=

ALLOWED_EMAILS=your-email@example.com
ALLOWED_DOMAINS=
REQUIRE_VERIFIED_EMAIL=true

AUTH_GATEWAY_JWT_KEY_ID=auth-gateway-main
AUTH_GATEWAY_JWT_PRIVATE_KEY_PATH=/run/secrets/auth-gateway-private-key.pem

JOB_CRM_URL=https://tools.liangwendev.com/job/api/auth/login
JOB_CRM_CLIENT_SECRET=replace-with-strong-job-crm-secret
JOB_CRM_REDIRECT_URI=https://tools.liangwendev.com/job/login/oauth2/code/auth-gateway
JOB_CRM_LOGOUT_REDIRECT_URI=https://tools.liangwendev.com/job/

INTERVIEW_URL=https://tools.liangwendev.com/interview/
INTERVIEW_CLIENT_SECRET=replace-with-strong-interview-secret
INTERVIEW_REDIRECT_URI=https://tools.liangwendev.com/interview/login/oauth2/code/auth-gateway
INTERVIEW_LOGOUT_REDIRECT_URI=https://tools.liangwendev.com/interview/

ONLINE_BILLING_URL=https://billing.liangwendev.com/
ONLINE_BILLING_CLIENT_SECRET=replace-with-strong-online-billing-secret
ONLINE_BILLING_REDIRECT_URI=https://billing.liangwendev.com/login/oauth2/code/auth-gateway
ONLINE_BILLING_LOGOUT_REDIRECT_URI=https://billing.liangwendev.com/
```

- [ ] **Step 2: Update systemd env example**

Modify `deploy/systemd/auth-gateway.env.example` so it matches the VPS service shape:

```text
PORT=19090
SERVER_ADDRESS=127.0.0.1
AUTH_GATEWAY_ISSUER=https://auth.liangwendev.com
SESSION_COOKIE_SECURE=true
AUTH_GATEWAY_PRODUCTION_SAFETY_ENABLED=true

GOOGLE_CLIENT_ID=replace-with-google-client-id
GOOGLE_CLIENT_SECRET=replace-with-google-client-secret

DATABASE_URL=jdbc:postgresql://127.0.0.1:5432/auth_gateway
DATABASE_USERNAME=auth_gateway
DATABASE_PASSWORD=replace-with-strong-postgres-password
JPA_DDL_AUTO=update
H2_CONSOLE_ENABLED=false

ADMIN_ENABLED=true
ADMIN_DOCKER_ENABLED=true
ADMIN_SYSTEMD_ENABLED=true
ADMIN_PORTS_ENABLED=true
ADMIN_ALLOWED_PROXY_IPS=
ADMIN_ACCESS_TOKEN=

ALLOWED_EMAILS=your-email@example.com
ALLOWED_DOMAINS=
REQUIRE_VERIFIED_EMAIL=true

AUTH_GATEWAY_JWT_PRIVATE_KEY_PATH=/etc/auth-gateway/auth-gateway-private-key.pem
AUTH_GATEWAY_JWT_KEY_ID=auth-gateway-main

JOB_CRM_URL=https://tools.liangwendev.com/job/api/auth/login
JOB_CRM_CLIENT_SECRET=replace-with-strong-job-crm-secret
JOB_CRM_REDIRECT_URI=https://tools.liangwendev.com/job/login/oauth2/code/auth-gateway
JOB_CRM_LOGOUT_REDIRECT_URI=https://tools.liangwendev.com/job/

INTERVIEW_URL=https://tools.liangwendev.com/interview/
INTERVIEW_CLIENT_SECRET=replace-with-strong-interview-secret
INTERVIEW_REDIRECT_URI=https://tools.liangwendev.com/interview/login/oauth2/code/auth-gateway
INTERVIEW_LOGOUT_REDIRECT_URI=https://tools.liangwendev.com/interview/

ONLINE_BILLING_URL=https://billing.liangwendev.com/
ONLINE_BILLING_CLIENT_SECRET=replace-with-strong-online-billing-secret
ONLINE_BILLING_REDIRECT_URI=https://billing.liangwendev.com/login/oauth2/code/auth-gateway
ONLINE_BILLING_LOGOUT_REDIRECT_URI=https://billing.liangwendev.com/
```

- [ ] **Step 3: Update Nginx proxy port**

Modify `deploy/nginx/auth-gateway.conf`:

```nginx
proxy_pass http://127.0.0.1:19090;
```

- [ ] **Step 4: Update README production sections**

Modify `README.md`:

- Add a "Stage-1 Production Baseline" section listing allowlist, persistent key, secure cookie, Postgres, app-level admin protection, and `scripts/prod-check.sh`.
- Change systemd runtime examples from `PORT=8080` to `PORT=19090` plus `SERVER_ADDRESS=127.0.0.1`.
- Change Interview production URLs from `interview.liangwendev.com` to `tools.liangwendev.com/interview`.
- Mention that `/admin/**` is protected by both Nginx 404 and application-level loopback/token checks.
- Link `docs/operations/h2-to-postgres.md`.

- [ ] **Step 5: Verify URL drift is gone**

Run:

```powershell
rg -n "interview\\.liangwendev\\.com|PORT=8080|proxy_pass http://127\\.0\\.0\\.1:8080" README.md .env.production.example deploy\\systemd\\auth-gateway.env.example deploy\\nginx\\auth-gateway.conf
```

Expected: no matches for the stale production Interview host or stale systemd proxy port. `PORT=8080` may remain in `.env.production.example` only for Docker Compose; if it appears elsewhere, inspect and correct it.

- [ ] **Step 6: Commit task 6**

Run:

```powershell
git add README.md .env.production.example deploy/systemd/auth-gateway.env.example deploy/nginx/auth-gateway.conf
git commit -m "docs: align production deployment configuration"
```

---

### Task 7: Full Local Verification And Push

**Files:**
- Review all changed files from tasks 1-6.

- [ ] **Step 1: Run full tests**

Run:

```powershell
.\mvnw.cmd test
```

Expected: PASS.

- [ ] **Step 2: Run stale-text scans**

Run:

```powershell
rg -n "GOOGLE_CLIENT_SECRET=.*GOCSPX|AUTH_GATEWAY_JWT_PRIVATE_KEY=.*BEGIN|BEGIN PRIVATE KEY|must-not-ship|unfinished-marker" .
```

Expected: no committed real Google secret, no inline private key, and no unfinished markers. Example values such as `replace-with-google-client-id` are acceptable.

- [ ] **Step 3: Review git diff**

Run:

```powershell
git status --short --branch
git log --oneline -6
```

Expected: clean working tree after task commits, and recent commits match the production-hardening slices.

- [ ] **Step 4: Push main**

Run:

```powershell
git push origin main
```

Expected: push succeeds and GitHub Actions starts for `main`.

---

### Task 8: VPS Deployment And Production Evidence

**Files:**
- Use repository scripts plus VPS files under `/etc/auth-gateway`, `/opt/auth-gateway`, and Nginx config.

- [ ] **Step 1: Verify GitHub Actions deployment**

After push, confirm the CI/CD workflow for the latest commit is green. If workflow deploys automatically, inspect the live service after it finishes.

- [ ] **Step 2: Prepare the VPS env without printing secrets**

SSH to the VPS and inspect safe values only:

```bash
sudo awk -F= '
  /^(PORT|SERVER_ADDRESS|AUTH_GATEWAY_ISSUER|SESSION_COOKIE_SECURE|DATABASE_URL|DATABASE_USERNAME|JPA_DDL_AUTO|H2_CONSOLE_ENABLED|ADMIN_ENABLED|ALLOWED_EMAILS|ALLOWED_DOMAINS|REQUIRE_VERIFIED_EMAIL|AUTH_GATEWAY_JWT_PRIVATE_KEY_PATH|AUTH_GATEWAY_JWT_KEY_ID|JOB_CRM_URL|INTERVIEW_URL)=/ {
    print
  }
' /etc/auth-gateway/auth-gateway.env
```

Expected safe values:

```text
PORT=19090
SERVER_ADDRESS=127.0.0.1
AUTH_GATEWAY_ISSUER=https://auth.liangwendev.com
SESSION_COOKIE_SECURE=true
DATABASE_URL=jdbc:postgresql://127.0.0.1:5432/auth_gateway
DATABASE_USERNAME=auth_gateway
JPA_DDL_AUTO=update
H2_CONSOLE_ENABLED=false
ADMIN_ENABLED=true
ALLOWED_EMAILS=<non-empty owner email list>
REQUIRE_VERIFIED_EMAIL=true
AUTH_GATEWAY_JWT_PRIVATE_KEY_PATH=/etc/auth-gateway/auth-gateway-private-key.pem
AUTH_GATEWAY_JWT_KEY_ID=auth-gateway-main
JOB_CRM_URL=https://tools.liangwendev.com/job/api/auth/login
INTERVIEW_URL=https://tools.liangwendev.com/interview/
```

- [ ] **Step 3: Migrate H2 to PostgreSQL if the live env still uses H2**

If `DATABASE_URL` starts with `jdbc:h2:`, follow `docs/operations/h2-to-postgres.md` and run:

```bash
sudo H2_DATABASE_PATH=/opt/auth-gateway/data/auth-gateway scripts/h2-to-postgres-dry-run.sh
```

After backup/export review, create PostgreSQL, import data, and switch `/etc/auth-gateway/auth-gateway.env` to PostgreSQL.

- [ ] **Step 4: Restart and run production check**

Run:

```bash
sudo systemctl restart auth-gateway.service
sudo systemctl status auth-gateway.service --no-pager --lines=20
sudo bash /opt/auth-gateway/current-release/scripts/prod-check.sh
```

Expected: service is active and `prod-check.sh` exits `0`.

- [ ] **Step 5: Verify public endpoints**

Run:

```bash
curl -fsS http://127.0.0.1:19090/actuator/health
curl -fsS https://auth.liangwendev.com/.well-known/openid-configuration
curl -fsS https://auth.liangwendev.com/oauth2/jwks
curl -sS -o /dev/null -w '%{http_code}\n' https://auth.liangwendev.com/admin/services
```

Expected:

```text
{"status":"UP"}
openid configuration JSON with issuer https://auth.liangwendev.com
JWKS JSON with keys
404
```

- [ ] **Step 6: Verify app-level admin protection through loopback and public proxy path**

Run:

```bash
curl -sS -o /dev/null -w '%{http_code}\n' http://127.0.0.1:19090/admin/services
curl -sS -H 'X-Forwarded-For: 203.0.113.10' -o /dev/null -w '%{http_code}\n' http://127.0.0.1:19090/admin/services
```

Expected:

```text
200
404
```

- [ ] **Step 7: Verify downstream login still works**

Open these URLs in a browser:

```text
https://auth.liangwendev.com/
https://tools.liangwendev.com/job/
https://tools.liangwendev.com/interview/
```

Expected:

- Gateway portal loads after Google login.
- Job CRM either recognizes the gateway session or redirects through `auth.liangwendev.com/oauth2/authorize`.
- Interview Intelligence either recognizes the gateway session or redirects through `auth.liangwendev.com/oauth2/authorize`.
- Logout does not leave an inconsistent default-user session.

- [ ] **Step 8: Record completion evidence**

Add a short deployment note to the final response with:

- latest commit SHA,
- `mvnw test` result,
- `prod-check.sh` result,
- public admin HTTP status,
- database kind from `/actuator/info` or `prod-check.sh`,
- confirmation that no secret values were printed.

Do not mark the goal complete unless every acceptance criterion in `docs/superpowers/specs/2026-07-05-auth-gateway-production-hardening-design.md` has direct evidence.
