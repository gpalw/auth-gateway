package dev.liangwen.relay;

import com.sun.net.httpserver.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.system.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import tools.jackson.databind.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:relay;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
    "relay.pepper=test-only-hash-pepper-abcdefghijklmnopqrstuvwxyz",
    "relay.admin-token=test-only-admin-token-abcdefghijklmnopqrstuvwxyz", "relay.timeout-seconds=2"
})
@ExtendWith(OutputCaptureExtension.class)
class RelayIntegrationTest {
    static final String ADMIN="test-only-admin-token-abcdefghijklmnopqrstuvwxyz";
    static final Fake FAKE=new Fake();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("relay.upstream",()->FAKE.origin+"/v1");}
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired Ledger ledger;
    @Autowired org.springframework.transaction.PlatformTransactionManager tm;
    @Autowired Secrets secrets;
    @Autowired RelaySettings settings;
    static final MutableClock CLOCK=new MutableClock();
    @org.springframework.boot.test.context.TestConfiguration static class TimeConfig {
        @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary Clock testClock(){return CLOCK;}
    }
    final HttpClient client=HttpClient.newHttpClient();
    @BeforeEach void reset(){ db.update("DELETE FROM relay_key_event");db.update("DELETE FROM relay_event");db.update("DELETE FROM relay_request");db.update("DELETE FROM relay_key");FAKE.reset();CLOCK.now=Instant.parse("2026-11-14T20:00:00Z"); }
    @AfterAll static void close(){FAKE.close();}
    record Key(String id,String token){}
    Key key(String budget) throws Exception {
        var response=send("POST","/admin/keys",ADMIN,"{\"name\":\"fixture\",\"models\":[\"mock-text\"],\"monthly_usd\":"+budget+",\"total_usd\":"+budget+"}");
        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode b=json.readTree(response.body());return new Key(b.path("id").asString(),b.path("token").asString());
    }
    static String chat(){return "{\"model\":\"mock-text\",\"messages\":[{\"role\":\"user\",\"content\":\"private-fixture-prompt\"}],\"max_completion_tokens\":100}";}
    HttpResponse<String> send(String method,String path,String token,String body,String...headers)throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Authorization","Bearer "+token).header("Content-Type","application/json");
        for(int i=0;i<headers.length;i+=2)b.header(headers[i],headers[i+1]);
        return client.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void createForwardSettleRevokeAndNeverPersistSecretsOrContent(CapturedOutput logs)throws Exception {
        Key k=key("1");assertThat(k.token()).matches("sk-lw-.*_[A-Za-z0-9_-]{43}");
        var result=send("POST","/v1/chat/completions",k.token(),chat());
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(FAKE.authorization).isEqualTo("Bearer mock-provider-only");
        assertThat(FAKE.request).contains("max_completion_tokens").doesNotContain(k.token());
        var row=db.queryForMap("SELECT * FROM relay_request");
        assertThat(row.get("STATUS")).isEqualTo("SETTLED");
        assertThat((BigDecimal)row.get("COST")).isEqualByComparingTo("0.000018");
        assertThat((BigDecimal)row.get("HELD")).isEqualByComparingTo("0");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM relay_event",Integer.class)).isEqualTo(2);
        String audit=db.queryForList("SELECT * FROM relay_request").toString()+db.queryForList("SELECT * FROM relay_key").toString()+db.queryForList("SELECT * FROM relay_event").toString();
        assertThat(audit+logs.getAll()+send("GET","/admin/keys",ADMIN,null).body()+send("GET","/admin/usage",ADMIN,null).body())
            .doesNotContain(k.token(),ADMIN,"mock-provider-only","private-fixture-prompt","private-fixture-output");
        assertThat(send("POST","/admin/keys/"+k.id()+"/revoke",ADMIN,"{}").statusCode()).isEqualTo(200);
        assertThat(send("POST","/v1/chat/completions",k.token(),chat()).statusCode()).isEqualTo(401);
        assertThat(FAKE.calls.get()).isEqualTo(1);
    }
    @Test void twentyFourConcurrentRequestsReserveAcrossDatabaseTransactions()throws Exception {
        Key k=key("0.15");FAKE.delayMillis=500;
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch start=new CountDownLatch(1);List<Future<Integer>> results=new ArrayList<>();
            for(int i=0;i<24;i++)results.add(pool.submit(()->{start.await();return send("POST","/v1/chat/completions",k.token(),chat()).statusCode();}));
            start.countDown();List<Integer> codes=new ArrayList<>();for(var f:results)codes.add(f.get(20,TimeUnit.SECONDS));
            assertThat(codes).allMatch(c->c==200 || c==429);
            assertThat(codes.stream().filter(c->c==429).count()).isGreaterThan(0);
            assertThat(FAKE.maxActive.get()).isLessThanOrEqualTo(2);
            assertThat(FAKE.calls.get()).isEqualTo(codes.stream().filter(c->c==200).count());
            assertThat(db.queryForObject("SELECT SUM(cost+held) FROM relay_request",BigDecimal.class)).isLessThanOrEqualTo(new BigDecimal("0.15"));
            assertThat(db.queryForObject("SELECT COUNT(*) FROM relay_request WHERE held<0 OR cost<0",Integer.class)).isZero();
        }
    }
    @Test void rejectInvalidRequestsBeforeUpstream()throws Exception {
        Key k=key("1");
        assertThat(send("POST","/v1/chat/completions","invalid",chat()).statusCode()).isEqualTo(401);
        for(String body:List.of(chat().replace("mock-text","unknown"),chat().replace("100","999999"),chat().replace("\"max_completion_tokens\":100","\"upstream\":\"http://example.test\""),chat().replace("\"private-fixture-prompt\"","[{\"type\":\"image_url\"}]"),"{broken"))
            assertThat(send("POST","/v1/chat/completions",k.token(),body).statusCode()).isBetween(400,499);
        assertThat(send("POST","/v1/embeddings",k.token(),chat()).statusCode()).isEqualTo(404);
        assertThat(send("POST","/v1/chat/completions?url=x",k.token(),chat()).statusCode()).isEqualTo(400);
        assertThat(send("POST","/v1/chat/completions",k.token(),"x".repeat(33000)).statusCode()).isEqualTo(413);
        Key exhausted=key("0");assertThat(send("POST","/v1/chat/completions",exhausted.token(),chat()).statusCode()).isEqualTo(429);
        assertThat(FAKE.calls.get()).isZero();
    }
    @Test void adminRequiresTokenAndOriginalLoopbackWithoutForwarding()throws Exception {
        assertThat(send("GET","/admin/keys","bad",null).statusCode()).isEqualTo(404);
        assertThat(send("GET","/admin/keys",ADMIN,null,"X-Forwarded-For","127.0.0.1").statusCode()).isEqualTo(404);
        assertThat(send("GET","/admin/keys",ADMIN,null,"Forwarded","for=127.0.0.1").statusCode()).isEqualTo(404);
        assertThat(send("POST","/admin/keys",ADMIN,"{\"name\":\"bad\",\"models\":[\"mock-text\"],\"monthly_usd\":-1,\"total_usd\":1}").statusCode()).isEqualTo(400);
        assertThat(send("POST","/admin/keys",ADMIN,"{\"name\":\"bad\",\"models\":[\"mock-text\"],\"monthly_usd\":1e100,\"total_usd\":1}").statusCode()).isEqualTo(400);
    }
    @Test void missingOrInvalidUsageRetainsLiability()throws Exception {
        Key k=key("1");FAKE.mode="missing";
        assertThat(send("POST","/v1/chat/completions",k.token(),chat()).statusCode()).isEqualTo(200);
        assertThat(db.queryForObject("SELECT status FROM relay_request",String.class)).isEqualTo("UNRESOLVED");
        assertThat(db.queryForObject("SELECT held FROM relay_request",BigDecimal.class)).isGreaterThan(BigDecimal.ZERO);
    }
    @Test void safeProviderErrorsAndAmbiguousFailures(CapturedOutput logs)throws Exception {
        Key k=key("1");FAKE.mode="429";
        var result=send("POST","/v1/chat/completions",k.token(),chat());
        assertThat(result.statusCode()).isEqualTo(429);assertThat(result.body()+logs.getAll()).doesNotContain("provider-secret-fixture");
        assertThat(db.queryForObject("SELECT status FROM relay_request",String.class)).isEqualTo("REJECTED");
        FAKE.mode="500";assertThat(send("POST","/v1/chat/completions",k.token(),chat()).statusCode()).isEqualTo(500);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM relay_request WHERE status='UNRESOLVED'",Integer.class)).isEqualTo(1);
    }
    @Test void settlementIsIdempotent()throws Exception {
        Key k=key("1");var r=ledger.reserve(k.token(),"mock-text","/v1/chat/completions",65536,100);
        ledger.finish(r,10L,5L,4L,"req_fixture",false);ledger.finish(r,10L,5L,4L,"req_fixture",false);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM relay_event",Integer.class)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT cost FROM relay_request",BigDecimal.class)).isEqualByComparingTo("0.000018");
    }
    @Test void rotationExpirySourceAndPolicyChanges()throws Exception {
        Key k=key("1");var rotated=send("POST","/admin/keys/"+k.id()+"/rotate",ADMIN,"{}");
        assertThat(rotated.statusCode()).isEqualTo(201);String next=json.readTree(rotated.body()).path("token").asString();
        assertThat(send("POST","/v1/chat/completions",k.token(),chat()).statusCode()).isEqualTo(401);
        String policy="{\"models\":[\"mock-text\"],\"monthly_usd\":1,\"total_usd\":1,\"expires_at\":\"2026-11-14T20:01:00Z\",\"rpm\":1,\"max_concurrent\":1,\"source_cidrs\":[\"127.0.0.0/8\"]}";
        assertThat(send("PUT","/admin/keys/"+k.id()+"/policy",ADMIN,policy).statusCode()).isEqualTo(200);
        assertThat(send("POST","/v1/chat/completions",next,chat(),"X-Forwarded-For","127.0.0.1").statusCode()).isEqualTo(403);
        assertThat(send("POST","/v1/chat/completions",next,chat()).statusCode()).isEqualTo(200);
        assertThat(send("POST","/v1/chat/completions",next,chat()).body()).contains("gateway_rate_exceeded");
        CLOCK.now=CLOCK.now.plusSeconds(60);
        assertThat(send("POST","/v1/chat/completions",next,chat()).statusCode()).isEqualTo(401);
        assertThat(FAKE.calls.get()).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM relay_key_event",Integer.class)).isEqualTo(3);
    }
    @Test void sourceDenialAndIpv6CidrAreNumericOnly()throws Exception {
        var k=ledger.create("source",Set.of("mock-text"),BigDecimal.ONE,BigDecimal.ONE,new Ledger.Policy(null,60,2,List.of("203.0.113.0/24")));
        assertThat(send("POST","/v1/chat/completions",String.valueOf(k.get("token")),chat()).statusCode()).isEqualTo(403);
        assertThat(FAKE.calls.get()).isZero();
        assertThat(SourcePolicy.permits("2001:db8::1","2001:db8::/32")).isTrue();
        assertThat(SourcePolicy.permits("2001:db9::1","2001:db8::/32")).isFalse();
        assertThatThrownBy(()->SourcePolicy.validate("localhost/8")).isInstanceOf(RelayError.class);
        var noProxy=new SourcePolicy(settings);
        assertThatThrownBy(()->noProxy.client("203.0.113.2",List.of("127.0.0.1"),false)).isInstanceOf(RelayError.class);
        var withProxy=new SourcePolicy(new RelaySettings(settings.mode(),settings.upstream(),settings.providerKey(),settings.pepper(),settings.adminToken(),settings.maxBodyBytes(),settings.maxResponseBytes(),settings.timeoutSeconds(),settings.prices(),List.of("127.0.0.1")));
        assertThat(withProxy.client("127.0.0.1",List.of("127.0.0.1, 203.0.113.9"),false)).isEqualTo("203.0.113.9");
    }
    @Test void rpmWindowConcurrencyAndRetryAdmissionUseDatabaseState()throws Exception {
        var k=ledger.create("rate",Set.of("mock-text"),BigDecimal.TEN,BigDecimal.TEN,new Ledger.Policy(null,1,1,List.of()));String token=String.valueOf(k.get("token"));
        var r=ledger.reserve(token,"mock-text","/v1/chat/completions",65536,100,"127.0.0.1","same-retry");
        assertThatThrownBy(()->ledger.reserve(token,"mock-text","/v1/chat/completions",65536,100,"127.0.0.1","same-retry")).hasMessage("idempotency_key_already_used");
        CLOCK.now=CLOCK.now.plusSeconds(60);
        assertThatThrownBy(()->ledger.reserve(token,"mock-text","/v1/chat/completions",65536,100)).hasMessage("gateway_concurrency_exceeded");
        ledger.finish(r,10L,5L,0L,null,false);
        assertThat(ledger.reserve(token,"mock-text","/v1/chat/completions",65536,100)).isNotNull();
    }
    @Test void independentWorkersCannotOverReserveOrDoubleChargeRetries()throws Exception {
        Key k=key("0.15");Ledger worker2=new Ledger(db,tm,secrets,settings,CLOCK);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            List<Future<Boolean>> tasks=new ArrayList<>();CountDownLatch go=new CountDownLatch(1);
            for(int i=0;i<24;i++){Ledger worker=i%2==0?ledger:worker2;tasks.add(pool.submit(()->{go.await();try{worker.reserve(k.token(),"mock-text","/v1/chat/completions",65536,100);return true;}catch(RelayError e){assertThat(e.status).isEqualTo(429);return false;}}));}
            go.countDown();int successes=0;for(var f:tasks)if(f.get(15,TimeUnit.SECONDS))successes++;
            assertThat(successes).isEqualTo(2);
            assertThat(db.queryForObject("SELECT SUM(cost+held) FROM relay_request",BigDecimal.class)).isLessThanOrEqualTo(new BigDecimal("0.15"));
        }
    }
    @Test void manualReconciliationPreservesEventsAndDoesNotDoubleApply()throws Exception {
        Key k=key("1");FAKE.mode="missing";send("POST","/v1/chat/completions",k.token(),chat());
        String id=db.queryForObject("SELECT id FROM relay_request",String.class);
        String b="{\"verified_cost_usd\":0.00002,\"reason\":\"provider_usage_verified\"}";
        assertThat(send("POST","/admin/requests/"+id+"/reconcile",ADMIN,b).statusCode()).isEqualTo(200);
        assertThat(send("POST","/admin/requests/"+id+"/reconcile",ADMIN,b).statusCode()).isEqualTo(409);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM relay_event",Integer.class)).isEqualTo(3);
        assertThat(db.queryForObject("SELECT cost FROM relay_request",BigDecimal.class)).isEqualByComparingTo("0.00002");
        assertThat(send("GET","/admin/usage",ADMIN,null).body()).contains("daily","monthly","outcomes");
    }
    @Test void utcMonthRolloverRetainsOldLiabilityAndPriceVersion()throws Exception {
        Key k=key("0.1");CLOCK.now=Instant.parse("2026-11-30T23:59:59Z");
        var old=ledger.reserve(k.token(),"mock-text","/v1/chat/completions",65536,100);
        CLOCK.now=CLOCK.now.plusSeconds(2);
        assertThatThrownBy(()->ledger.reserve(k.token(),"mock-text","/v1/chat/completions",65536,100)).hasMessage("gateway_budget_exceeded");
        ledger.finish(old,10L,5L,0L,null,false);
        ledger.reserve(k.token(),"mock-text","/v1/chat/completions",65536,100);
        assertThat(db.queryForList("SELECT charge_month FROM relay_request",String.class)).containsExactlyInAnyOrder("2026-11","2026-12");
        assertThat(db.queryForList("SELECT price_version FROM relay_request",String.class)).containsOnly("mock-v1");
    }
    static class MutableClock extends Clock {
        volatile Instant now=Instant.parse("2026-11-14T20:00:00Z");
        public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now;}
    }
    static class Fake implements AutoCloseable {
        final HttpServer server;final String origin;final ExecutorService executor=Executors.newVirtualThreadPerTaskExecutor();
        final AtomicInteger calls=new AtomicInteger(),active=new AtomicInteger(),maxActive=new AtomicInteger();
        volatile String mode="ok",authorization="",request="";volatile int delayMillis;
        Fake(){try{server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);origin="http://127.0.0.1:"+server.getAddress().getPort();server.createContext("/",this::handle);server.setExecutor(executor);server.start();}catch(Exception e){throw new IllegalStateException(e);}}
        void reset(){calls.set(0);active.set(0);maxActive.set(0);mode="ok";delayMillis=0;authorization="";request="";}
        void handle(HttpExchange x){
            calls.incrementAndGet();maxActive.accumulateAndGet(active.incrementAndGet(),Math::max);
            try{authorization=x.getRequestHeaders().getFirst("Authorization");request=new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
                if(delayMillis>0)Thread.sleep(delayMillis);
                int code=mode.equals("429")?429:mode.equals("500")?500:200;
                String usage=mode.equals("missing")?"":" ,\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"prompt_tokens_details\":{\"cached_tokens\":4}}";
                String body=code==200?"{\"id\":\"chatcmpl-fixture\",\"object\":\"chat.completion\",\"model\":\"mock-text\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"private-fixture-output\"},\"finish_reason\":\"stop\"}]"+usage+"}":"{\"error\":\"provider-secret-fixture\"}";
                byte[] b=body.getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type","application/json");x.getResponseHeaders().set("x-request-id","req_fixture");x.sendResponseHeaders(code,b.length);x.getResponseBody().write(b);
            }catch(Exception ignored){}finally{active.decrementAndGet();x.close();}
        }
        public void close(){server.stop(0);executor.shutdownNow();}
    }
}
