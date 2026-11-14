package dev.liangwen.relay;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.*;

@Component
final class Upstream {
    private final RelaySettings settings;
    private final Ledger ledger;
    private final ObjectMapper json;
    private final HttpClient client;
    private final ScheduledExecutorService deadlines=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"relay-deadline");t.setDaemon(true);return t;});
    Upstream(RelaySettings settings,Ledger ledger,ObjectMapper json) {
        this.settings=settings;this.ledger=ledger;this.json=json;
        client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(settings.timeoutSeconds()))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    }
    record Result(int status,byte[] body) {}
    Result chat(Payloads.Call call,Ledger.Reservation reservation) {
        try {
            HttpRequest request=HttpRequest.newBuilder(URI.create(settings.upstream()+"/chat/completions"))
                .timeout(Duration.ofSeconds(settings.timeoutSeconds())).header("Authorization","Bearer "+settings.providerKey())
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(call.body())).build();
            HttpResponse<InputStream> response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());
            try(InputStream in=response.body()) {
                ScheduledFuture<?> deadline=deadlines.schedule(()->{try{in.close();}catch(IOException ignored){}},settings.timeoutSeconds(),TimeUnit.SECONDS);
                try {
                    if (response.statusCode()!=200) {
                        // An HTTP rejection with no generated output is safe to release; 5xx/redirect ambiguity is held.
                        boolean free=java.util.Set.of(400,401,403,404,422,429).contains(response.statusCode());
                        ledger.finish(reservation,null,null,null,null,free);
                        int status=response.statusCode()>=400 && response.statusCode()<=599?response.statusCode():502;
                        throw new RelayError(status,"upstream_rejected");
                    }
                    if (!response.headers().firstValue("Content-Type").orElse("").toLowerCase().startsWith("application/json")) throw new RelayError(502,"invalid_upstream_response");
                    byte[] bytes=in.readNBytes(settings.maxResponseBytes()+1);
                    if(bytes.length>settings.maxResponseBytes()) throw new RelayError(502,"upstream_response_too_large");
                    JsonNode body=json.readTree(bytes);
                    if (!body.isObject() || !body.path("choices").isArray() || !call.model().equals(body.path("model").asString())) throw new RelayError(502,"invalid_upstream_response");
                    JsonNode usage=body.path("usage");
                    ledger.finish(reservation,number(usage,"prompt_tokens"),number(usage,"completion_tokens"),
                        usage.has("prompt_tokens_details")?number(usage.path("prompt_tokens_details"),"cached_tokens"):0L,
                        response.headers().firstValue("x-request-id").orElse(null),false);
                    return new Result(200,bytes);
                } finally {deadline.cancel(false);}
            }
        } catch(RelayError e) { ledger.finish(reservation,null,null,null,null,false);throw e; }
        catch(Exception e) {
            if(e instanceof InterruptedException) Thread.currentThread().interrupt();
            ledger.finish(reservation,null,null,null,null,false);
            throw new RelayError(502,"upstream_unavailable");
        }
    }
    static Long number(JsonNode node,String name) {
        JsonNode n=node.path(name);return n.isIntegralNumber() && n.canConvertToLong()?n.asLong():null;
    }
    @jakarta.annotation.PreDestroy void close() { deadlines.shutdownNow();client.close(); }
}
