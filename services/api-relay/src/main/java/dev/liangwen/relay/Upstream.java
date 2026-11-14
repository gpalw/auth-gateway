package dev.liangwen.relay;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import jakarta.servlet.http.HttpServletResponse;
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
        client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(settings.timeoutSeconds())).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    void relay(Payloads.Call call,Ledger.Reservation reservation,HttpServletResponse downstream) {
        try {
            HttpRequest request=HttpRequest.newBuilder(URI.create(settings.upstream()+call.endpoint()))
                .timeout(Duration.ofSeconds(settings.timeoutSeconds())).header("Authorization","Bearer "+settings.providerKey())
                .header("Content-Type","application/json").header("Accept",call.stream()?"text/event-stream":"application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(call.body())).build();
            HttpResponse<InputStream> response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());
            try(InputStream in=response.body()) {
                ScheduledFuture<?> deadline=deadlines.schedule(()->{try{in.close();}catch(IOException ignored){}},settings.timeoutSeconds(),TimeUnit.SECONDS);
                try {
                    if (response.statusCode()!=200) {
                        boolean free=Set.of(400,401,403,404,422,429).contains(response.statusCode());
                        ledger.finish(reservation,null,null,null,null,free);
                        int status=response.statusCode()>=400 && response.statusCode()<=599?response.statusCode():502;
                        throw new RelayError(status,"upstream_rejected");
                    }
                    String type=response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
                    String upstreamId=response.headers().firstValue("x-request-id").orElse(null);
                    if(call.stream()) {
                        if(!type.startsWith("text/event-stream"))throw new RelayError(502,"invalid_upstream_response");
                        downstream.setStatus(200);downstream.setContentType("text/event-stream;charset=UTF-8");downstream.setHeader("X-Accel-Buffering","no");
                        stream(in,downstream.getOutputStream(),call,reservation,upstreamId);
                    } else {
                        if (!type.startsWith("application/json")) throw new RelayError(502,"invalid_upstream_response");
                        byte[] bytes=in.readNBytes(settings.maxResponseBytes()+1);
                        if(bytes.length>settings.maxResponseBytes()) throw new RelayError(502,"upstream_response_too_large");
                        JsonNode body=json.readTree(bytes);boolean responses=call.endpoint().equals("/responses");
                        if (!body.isObject() || body.hasNonNull("error") || !call.model().equals(body.path("model").asString())
                                || !(responses?body.path("output").isArray():body.path("choices").isArray())) throw new RelayError(502,"invalid_upstream_response");
                        settle(reservation,body.path("usage"),responses,upstreamId);
                        downstream.setStatus(200);downstream.setContentType("application/json");downstream.getOutputStream().write(bytes);
                    }
                } finally {deadline.cancel(false);}
            }
        } catch(RelayError e) { ledger.finish(reservation,null,null,null,null,false);throw e; }
        catch(Exception e) {
            if(e instanceof InterruptedException) Thread.currentThread().interrupt();
            ledger.finish(reservation,null,null,null,null,false);throw new RelayError(502,"upstream_unavailable");
        }
    }
    private void settle(Ledger.Reservation r,JsonNode usage,boolean responses,String upstreamId) {
        String input=responses?"input_tokens":"prompt_tokens",output=responses?"output_tokens":"completion_tokens",details=responses?"input_tokens_details":"prompt_tokens_details";
        ledger.finish(r,number(usage,input),number(usage,output),usage.hasNonNull(details)?number(usage.path(details),"cached_tokens"):0L,upstreamId,false);
    }
    private void stream(InputStream source,OutputStream target,Payloads.Call call,Ledger.Reservation r,String upstreamId)throws IOException {
        // Bound each event and total stream; flush each event, never collect the whole response.
        InputStream in=new BufferedInputStream(source,8192);ByteArrayOutputStream frame=new ByteArrayOutputStream();
        int total=0,lineLength=0;boolean terminal=false,responses=call.endpoint().equals("/responses");JsonNode usage=null;
        for(int value;(value=in.read())!=-1;){
            if(++total>settings.maxResponseBytes() || frame.size()>=65536)throw new RelayError(502,"upstream_response_too_large");
            frame.write(value);
            if(value=='\n'){
                if(lineLength==0){
                    byte[] bytes=frame.toByteArray();frame.reset();Event event=event(bytes);
                    if(event.data()!=null){
                        if(event.data().equals("[DONE]")){
                            if(responses)throw new RelayError(502,"invalid_upstream_stream");terminal=true;
                        }else {
                            JsonNode node;
                            try{node=json.readTree(event.data());}catch(Exception e){throw new RelayError(502,"invalid_upstream_stream");}
                            if(!node.isObject() || node.hasNonNull("error") || event.type().equals("error")
                                    || Set.of("error","response.failed","response.incomplete").contains(node.path("type").asString()))throw new RelayError(502,"upstream_stream_failed");
                            if(responses){
                                if(node.path("type").asString().equals("response.completed")){
                                    JsonNode response=node.path("response");
                                    if(!call.model().equals(response.path("model").asString()) || !"completed".equals(response.path("status").asString()))throw new RelayError(502,"invalid_upstream_stream");
                                    usage=response.path("usage");terminal=true;
                                }
                            }else {
                                if(!call.model().equals(node.path("model").asString()) || !node.path("choices").isArray())throw new RelayError(502,"invalid_upstream_stream");
                                if(node.hasNonNull("usage"))usage=node.path("usage");
                            }
                        }
                    }
                    target.write(bytes);target.flush();if(terminal)break;
                }
                lineLength=0;
            }else if(value!='\r')lineLength++;
        }
        if(!terminal)throw new RelayError(502,"upstream_stream_incomplete");
        if(usage==null)ledger.finish(r,null,null,null,upstreamId,false);else settle(r,usage,responses,upstreamId);
    }
    private record Event(String type,String data){}
    private static Event event(byte[] frame) {
        String text=new String(frame,StandardCharsets.UTF_8);StringBuilder data=new StringBuilder();String type="";boolean hasData=false;
        for(String line:text.split("\\r?\\n")){
            if(line.startsWith("data:")){if(hasData)data.append('\n');String value=line.substring(5);data.append(value.startsWith(" ")?value.substring(1):value);hasData=true;}
            else if(line.startsWith("event:")){type=line.substring(6).trim();if(!type.matches("[A-Za-z0-9._-]{1,100}"))throw new RelayError(502,"invalid_upstream_stream");}
            else if(!line.isEmpty() && !line.startsWith(":"))throw new RelayError(502,"unsupported_stream_field");
        }
        if(type.equals("error"))throw new RelayError(502,"upstream_stream_failed");return new Event(type,hasData?data.toString():null);
    }
    static Long number(JsonNode node,String name) {JsonNode n=node.path(name);return n.isIntegralNumber() && n.canConvertToLong()?n.asLong():null;}
    @jakarta.annotation.PreDestroy void close() {deadlines.shutdownNow();client.close();}
}
