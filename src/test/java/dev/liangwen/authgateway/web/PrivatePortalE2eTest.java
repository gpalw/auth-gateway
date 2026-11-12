package dev.liangwen.authgateway.web;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.assertThat;

/** Real browser + real Spring filters + real Nginx, with a loopback-only mock Google issuer.
 * This class and its issuer are test sources and are excluded from the deployable JAR. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "server.address=127.0.0.1", "server.forward-headers-strategy=framework",
    "spring.datasource.url=jdbc:h2:mem:private-portal-e2e;DB_CLOSE_DELAY=-1",
    "auth-gateway.production-safety.enabled=false", "private-portal.enabled=true",
    "identity.access.allowed-emails=lwxyq084891@gmail.com", "identity.access.allowed-domains=",
    "identity.access.require-verified-email=true",
    "identity.apps[0].id=fixture", "identity.apps[0].name=文档样例 · 测试数据",
    "identity.apps[0].description=用于搜索和分类验证，非真实私人目录。",
    "identity.apps[0].url=https://example.test/docs", "identity.apps[0].enabled=true",
    "identity.clients[0].client-id=fixture", "identity.clients[0].client-secret=test-only-placeholder",
    "private-portal.categories.fixture=资料",
    "spring.security.oauth2.client.registration.google.provider=fixture-google",
    "spring.security.oauth2.client.registration.google.client-id=fixture-client",
    "spring.security.oauth2.client.registration.google.scope=openid,email",
    "spring.security.oauth2.client.registration.google.client-secret=test-only-placeholder"
})
class PrivatePortalE2eTest {
    static final MockGoogle GOOGLE = new MockGoogle();
    @LocalServerPort int port;
    @DynamicPropertySource static void endpoints(DynamicPropertyRegistry r) {
        r.add("spring.security.oauth2.client.provider.fixture-google.issuer-uri", () -> GOOGLE.origin);
    }
    @AfterAll static void stop() { GOOGLE.server.stop(0); GOOGLE.executor.shutdownNow(); }
    @org.springframework.boot.test.context.TestConfiguration
    static class FailureDiagnostics {
        @org.springframework.context.annotation.Bean
        org.springframework.boot.web.servlet.FilterRegistrationBean<jakarta.servlet.Filter> fixtureFailureDiagnostic() {
            var bean = new org.springframework.boot.web.servlet.FilterRegistrationBean<jakarta.servlet.Filter>();
            bean.setFilter((request, response, chain) -> {
                var http = (jakarta.servlet.http.HttpServletRequest) request;
                if ("/login".equals(http.getRequestURI()) && http.getSession(false) != null) {
                    Object error = http.getSession(false).getAttribute("SPRING_SECURITY_LAST_EXCEPTION");
                    if (error instanceof org.springframework.security.oauth2.core.OAuth2AuthenticationException failure) {
                        GOOGLE.lastFailure = failure.getError().getErrorCode();
                        System.out.println("MOCK_OAUTH_FAILURE " + GOOGLE.lastFailure + " " + failure.getMessage());
                    }
                }
                chain.doFilter(request,response);
            });
            bean.setOrder(-200);
            return bean;
        }
    }
    @Test void browserAndNginxSecurityMatrix() throws Exception {
        String nginx = System.getenv("MVP_NGINX");
        org.junit.jupiter.api.Assumptions.assumeTrue(nginx != null, "Set MVP_NGINX to run real Nginx/browser E2E");
        ProcessBuilder builder = new ProcessBuilder("node", "scripts/verify-private-portal.cjs",
                Integer.toString(port), GOOGLE.origin, nginx);
        builder.redirectErrorStream(true).redirectOutput(Path.of("evidence", "browser-run.log").toFile());
        Process process = builder.start();
        boolean exited = process.waitFor(120, TimeUnit.SECONDS);
        if (!exited) process.destroyForcibly();
        assertThat(exited).as("browser timeout; see evidence/browser-run.log").isTrue();
        assertThat(process.exitValue()).withFailMessage(Files.readString(Path.of("evidence", "browser-run.log"))).isZero();
    }
    static class MockGoogle {
        final HttpServer server;
        final ExecutorService executor = Executors.newCachedThreadPool();
        final RSAKey key;
        final String origin;
        final Map<String,Map<String,String>> codes = new ConcurrentHashMap<>();
        volatile String email="lwxyq084891@gmail.com", mode="ok", lastFailure="";
        volatile boolean pkceMismatch;
        MockGoogle() {
            try {
                key = new RSAKeyGenerator(2048).keyID("test-only").generate();
                server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
                origin="http://127.0.0.1:"+server.getAddress().getPort();
                server.createContext("/", this::handle); server.setExecutor(executor); server.start();
            } catch(Exception e) {throw new IllegalStateException(e);}
        }
        void handle(HttpExchange x) {
            try {
                String path=x.getRequestURI().getPath(); Map<String,String> q=params(x.getRequestURI().getRawQuery());
                switch(path) {
                    case "/.well-known/openid-configuration" -> json(x,"{\"issuer\":\""+origin+"\",\"authorization_endpoint\":\""+origin+"/authorize\",\"token_endpoint\":\""+origin+"/token\",\"jwks_uri\":\""+origin+"/jwks\",\"userinfo_endpoint\":\""+origin+"/userinfo\",\"response_types_supported\":[\"code\"],\"subject_types_supported\":[\"public\"],\"id_token_signing_alg_values_supported\":[\"RS256\"],\"code_challenge_methods_supported\":[\"S256\"]}");
                    case "/jwks" -> json(x,new JWKSet(key.toPublicJWK()).toString());
                    case "/userinfo" -> json(x,"{\"sub\":\"fixture-"+email+"\",\"email\":\""+email+"\",\"email_verified\":"+!"unverified".equals(mode)+"}");
                    case "/scenario" -> {email=q.getOrDefault("email","lwxyq084891@gmail.com");mode=q.getOrDefault("mode","ok");lastFailure="";pkceMismatch=false;json(x,"{\"mock\":true}");}
                    case "/last-failure" -> json(x,"{\"code\":\""+lastFailure+"\",\"pkceMismatch\":"+pkceMismatch+"}");
                    case "/authorize" -> {
                        if (!"S256".equals(q.get("code_challenge_method")) || !q.containsKey("nonce") || !q.containsKey("state")) {x.sendResponseHeaders(400,-1);break;}
                        String code=UUID.randomUUID().toString(); Map<String,String> saved=new HashMap<>(q);saved.put("email",email);saved.put("mode",mode);codes.put(code,saved);
                        String state="bad_state".equals(mode)?"tampered":q.get("state");
                        x.getResponseHeaders().set("Location",q.get("redirect_uri")+"?code="+code+"&state="+URLEncoder.encode(state,StandardCharsets.UTF_8));x.sendResponseHeaders(302,-1);
                    }
                    case "/token" -> {
                        Map<String,String> body=params(new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                        Map<String,String> saved=codes.remove(body.get("code"));
                        String challenge=body.get("code_verifier")==null?"":Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(body.get("code_verifier").getBytes(StandardCharsets.US_ASCII)));
                        if (saved==null || !challenge.equals(saved.get("code_challenge")) || !Objects.equals(saved.get("redirect_uri"),body.get("redirect_uri"))) {
                            pkceMismatch = saved != null && !challenge.equals(saved.get("code_challenge"));
                            byte[] error="{\"error\":\"invalid_grant\"}".getBytes(StandardCharsets.UTF_8);
                            x.getResponseHeaders().set("Content-Type","application/json");
                            x.sendResponseHeaders(400,error.length);x.getResponseBody().write(error);break;
                        }
                        Instant now=Instant.now();
                        JWTClaimsSet claims=new JWTClaimsSet.Builder().issuer(origin).subject("fixture-"+saved.get("email")).audience("fixture-client")
                            .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(300)))
                            .claim("nonce","bad_nonce".equals(saved.get("mode"))?"tampered":saved.get("nonce"))
                            .claim("email",saved.get("email")).claim("email_verified",!"unverified".equals(saved.get("mode")))
                            .claim("name","Wen · 本地测试").build();
                        SignedJWT token=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),claims);token.sign(new RSASSASigner(key));
                        json(x,"{\"access_token\":\"test-only-placeholder\",\"token_type\":\"Bearer\",\"expires_in\":300,\"scope\":\"openid\",\"id_token\":\""+token.serialize()+"\"}");
                    }
                    default -> x.sendResponseHeaders(404,-1);
                }
            } catch(Exception e) {try{x.sendResponseHeaders(500,-1);}catch(Exception ignored){}} finally{x.close();}
        }
        static Map<String,String> params(String raw) {
            Map<String,String> p=new HashMap<>();if(raw!=null)for(String s:raw.split("&")){String[] kv=s.split("=",2);p.put(URLDecoder.decode(kv[0],StandardCharsets.UTF_8),kv.length>1?URLDecoder.decode(kv[1],StandardCharsets.UTF_8):"");}return p;
        }
        static void json(HttpExchange x,String body) throws Exception {byte[] bytes=body.getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type","application/json");x.sendResponseHeaders(200,bytes.length);x.getResponseBody().write(bytes);}
    }
}
