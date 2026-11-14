package dev.liangwen.relay;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.*;

@Component
final class RelayHttp extends OncePerRequestFilter {
    private final RelaySettings settings;
    private final Ledger ledger;
    private final Payloads payloads;
    private final Upstream upstream;
    private final ObjectMapper json;
    private final SourcePolicy sourcePolicy;
    RelayHttp(RelaySettings settings,Ledger ledger,Payloads payloads,Upstream upstream,ObjectMapper json,SourcePolicy sourcePolicy) {
        this.settings=settings;this.ledger=ledger;this.payloads=payloads;this.upstream=upstream;this.json=json;this.sourcePolicy=sourcePolicy;
    }
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws IOException {
        res.setHeader("Cache-Control","no-store");res.setHeader("X-Content-Type-Options","nosniff");
        try {
            String path=req.getRequestURI(), method=req.getMethod();
            if(req.getQueryString()!=null) throw new RelayError(400,"query_not_supported");
            if(path.equals("/health") && method.equals("GET")) {boolean up=ledger.healthy();reply(res,up?200:503,Map.of("status",up?"UP":"DOWN","mode",settings.mode()));return;}
            if(method.equals("GET") && Set.of("/dashboard","/dashboard.js","/dashboard.css").contains(path)) {
                // Public shell contains no data; every data request still requires the owner bearer and loopback peer.
                res.setHeader("Content-Security-Policy","default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'");
                res.setHeader("Referrer-Policy","no-referrer");
                String resource=path.equals("/dashboard")?"/dashboard.html":path;
                res.setContentType(resource.endsWith(".html")?"text/html;charset=UTF-8":resource.endsWith(".css")?"text/css":"application/javascript");
                try(InputStream in=RelayHttp.class.getResourceAsStream(resource)){if(in==null)throw new RelayError(404,"not_found");in.transferTo(res.getOutputStream());}return;
            }
            if(path.startsWith("/admin/")) { admin(req,res,path,method);return; }
            String token=Secrets.bearer(singleAuthorization(req));
            String source=sourcePolicy.client(req);
            Ledger.Key key=ledger.authorize(token,source);
            if(path.equals("/v1/models") && method.equals("GET")){
                List<Map<String,Object>> models=key.models().stream().sorted().filter(m->settings.prices().stream().anyMatch(p->p.model().equals(m)))
                    .map(m->Map.<String,Object>of("id",m,"object","model","created",0,"owned_by","relay")).toList();
                reply(res,200,Map.of("object","list","data",models));return;
            }
            if(!Set.of("/v1/chat/completions","/v1/responses").contains(path)) throw new RelayError(404,"endpoint_not_supported");
            if(!method.equals("POST")) throw new RelayError(405,"method_not_allowed");
            Payloads.Call call=path.equals("/v1/responses")?payloads.responses(body(req)):payloads.chat(body(req));
            List<String> retryHeaders=Collections.list(req.getHeaders("Idempotency-Key"));
            if(retryHeaders.size()>1 || !retryHeaders.isEmpty() && !retryHeaders.getFirst().matches("[A-Za-z0-9._:-]{1,128}"))throw new RelayError(400,"invalid_idempotency_key");
            Ledger.Reservation r=ledger.reserve(token,call.model(),path,call.inputBound(),call.outputBound(),source,retryHeaders.isEmpty()?null:retryHeaders.getFirst());
            res.setHeader("X-Relay-Request-Id",r.id());
            upstream.relay(call,r,res);
        } catch(RelayError e) {if(!res.isCommitted()) {if(e.status==429)res.setHeader("Retry-After","60");reply(res,e.status,Map.of("error",Map.of("message",e.code,"type","relay_error","code",e.code)));}
            else if(res.getContentType()!=null && res.getContentType().startsWith("text/event-stream"))try{res.getOutputStream().write("event: error\ndata: {\"error\":{\"code\":\"relay_stream_failed\",\"message\":\"relay_stream_failed\"}}\n\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));res.flushBuffer();}catch(IOException ignored){}
        }
        catch(Exception e) { if(!res.isCommitted()) reply(res,500,Map.of("error",Map.of("message","internal_error","type","relay_error","code","internal_error"))); }
    }
    private static String singleAuthorization(HttpServletRequest req) {
        List<String> headers=Collections.list(req.getHeaders("Authorization"));
        if(headers.size()!=1) throw new RelayError(401,"invalid_api_key");return headers.getFirst();
    }
    private void admin(HttpServletRequest req,HttpServletResponse res,String path,String method) throws IOException {
        // Use original TCP peer. Forwarding is disabled and any proxy headers fail closed here.
        if(!Set.of("127.0.0.1","0:0:0:0:0:0:0:1","::1").contains(req.getRemoteAddr())
                || Collections.list(req.getHeaderNames()).stream().anyMatch(h->h.equalsIgnoreCase("Forwarded") || h.toLowerCase(Locale.ROOT).startsWith("x-forwarded-"))
                || !Secrets.same(Secrets.bearer(singleAuthorization(req)),settings.adminToken())) throw new RelayError(404,"not_found");
        if(path.equals("/admin/keys") && method.equals("POST")) {
            JsonNode b=body(req);Payloads.allowed(b,Set.of("name","models","monthly_usd","total_usd","expires_at","rpm","max_concurrent","source_cidrs"));
            reply(res,201,ledger.create(Payloads.text(b,"name"),models(b),decimal(b,"monthly_usd"),decimal(b,"total_usd"),policy(b)));return;
        }
        if(path.equals("/admin/keys") && method.equals("GET")){reply(res,200,ledger.keys());return;}
        if(path.equals("/admin/usage") && method.equals("GET")){reply(res,200,ledger.usage());return;}
        if(path.matches("/admin/keys/[a-f0-9-]{36}/revoke") && method.equals("POST")) {
            ledger.revoke(path.split("/")[3]);reply(res,200,Map.of("revoked",true));return;
        }
        if(path.matches("/admin/keys/[a-f0-9-]{36}/rotate") && method.equals("POST")) {
            reply(res,201,ledger.rotate(path.split("/")[3]));return;
        }
        if(path.matches("/admin/keys/[a-f0-9-]{36}/policy") && method.equals("PUT")) {
            JsonNode b=body(req);Payloads.allowed(b,Set.of("models","monthly_usd","total_usd","expires_at","rpm","max_concurrent","source_cidrs"));
            ledger.policy(path.split("/")[3],models(b),decimal(b,"monthly_usd"),decimal(b,"total_usd"),policy(b));reply(res,200,Map.of("updated",true));return;
        }
        if(path.matches("/admin/requests/[a-f0-9-]{36}/reconcile") && method.equals("POST")) {
            JsonNode b=body(req);Payloads.allowed(b,Set.of("verified_cost_usd","reason"));ledger.reconcile(path.split("/")[3],decimal(b,"verified_cost_usd"),Payloads.text(b,"reason"));reply(res,200,Map.of("reconciled",true));return;
        }
        throw new RelayError(404,"not_found");
    }
    private static Set<String> models(JsonNode b) {
        if(!b.path("models").isArray())throw new RelayError(400,"invalid_models");
        Set<String> models=new HashSet<>();for(JsonNode m:b.path("models")){if(!m.isString())throw new RelayError(400,"invalid_models");models.add(m.asString());}return models;
    }
    private static Ledger.Policy policy(JsonNode b) {
        List<String> cidrs=new ArrayList<>();
        if(b.has("source_cidrs")){if(!b.path("source_cidrs").isArray())throw new RelayError(400,"invalid_source_cidrs");for(JsonNode c:b.path("source_cidrs")){if(!c.isString())throw new RelayError(400,"invalid_source_cidrs");cidrs.add(c.asString());}}
        java.time.Instant expires=null;
        if(b.hasNonNull("expires_at"))try{expires=java.time.Instant.parse(Payloads.text(b,"expires_at"));}catch(Exception e){throw new RelayError(400,"invalid_expiry");}
        return new Ledger.Policy(expires,integer(b,"rpm",60),integer(b,"max_concurrent",8),cidrs);
    }
    private static int integer(JsonNode b,String field,int defaultValue){
        if(!b.has(field))return defaultValue;if(!b.path(field).isIntegralNumber() || !b.path(field).canConvertToInt())throw new RelayError(400,"invalid_"+field);return b.path(field).asInt();
    }
    private static BigDecimal decimal(JsonNode b,String field) {
        if(!b.path(field).isNumber())throw new RelayError(400,"invalid_money");return b.path(field).decimalValue();
    }
    private JsonNode body(HttpServletRequest req) throws IOException {
        if(req.getContentType()==null || !req.getContentType().split(";",2)[0].trim().equalsIgnoreCase("application/json") || req.getHeader("Content-Encoding")!=null) throw new RelayError(415,"json_required");
        if(req.getContentLengthLong()>settings.maxBodyBytes())throw new RelayError(413,"payload_too_large");
        byte[] b=req.getInputStream().readNBytes(settings.maxBodyBytes()+1);
        if(b.length>settings.maxBodyBytes())throw new RelayError(413,"payload_too_large");
        try {JsonNode root=json.readTree(b);if(root==null || !root.isObject())throw new RelayError(400,"invalid_json");return root;}
        catch(RelayError e){throw e;}catch(Exception e){throw new RelayError(400,"invalid_json");}
    }
    private void reply(HttpServletResponse res,int status,Object value) throws IOException {
        res.setStatus(status);res.setContentType("application/json");res.getOutputStream().write(json.writeValueAsBytes(value));
    }
}
