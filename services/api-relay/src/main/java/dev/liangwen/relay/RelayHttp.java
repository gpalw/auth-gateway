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
    RelayHttp(RelaySettings settings,Ledger ledger,Payloads payloads,Upstream upstream,ObjectMapper json) {
        this.settings=settings;this.ledger=ledger;this.payloads=payloads;this.upstream=upstream;this.json=json;
    }
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws IOException {
        res.setHeader("Cache-Control","no-store");res.setHeader("X-Content-Type-Options","nosniff");
        try {
            String path=req.getRequestURI(), method=req.getMethod();
            if(req.getQueryString()!=null) throw new RelayError(400,"query_not_supported");
            if(path.equals("/health") && method.equals("GET")) {reply(res,200,Map.of("status","UP","mode",settings.mode()));return;}
            if(path.startsWith("/admin/")) { admin(req,res,path,method);return; }
            String token=Secrets.bearer(singleAuthorization(req));
            ledger.authenticate(token,false);
            if(!path.equals("/v1/chat/completions")) throw new RelayError(404,"endpoint_not_supported");
            if(!method.equals("POST")) throw new RelayError(405,"method_not_allowed");
            Payloads.Call call=payloads.chat(body(req));
            Ledger.Reservation r=ledger.reserve(token,call.model(),path,call.inputBound(),call.outputBound());
            res.setHeader("X-Relay-Request-Id",r.id());
            Upstream.Result result=upstream.chat(call,r);
            res.setStatus(result.status());res.setContentType("application/json");res.getOutputStream().write(result.body());
        } catch(RelayError e) {if(!res.isCommitted()) {if(e.status==429)res.setHeader("Retry-After","60");reply(res,e.status,Map.of("error",Map.of("message",e.code,"type","relay_error","code",e.code)));}}
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
            JsonNode b=body(req);Payloads.allowed(b,Set.of("name","models","monthly_usd","total_usd"));
            if(!b.path("models").isArray()) throw new RelayError(400,"invalid_models");
            Set<String> models=new HashSet<>();for(JsonNode m:b.path("models")){if(!m.isString())throw new RelayError(400,"invalid_models");models.add(m.asString());}
            reply(res,201,ledger.create(Payloads.text(b,"name"),models,decimal(b,"monthly_usd"),decimal(b,"total_usd")));return;
        }
        if(path.equals("/admin/keys") && method.equals("GET")){reply(res,200,ledger.keys());return;}
        if(path.equals("/admin/usage") && method.equals("GET")){reply(res,200,ledger.requests());return;}
        if(path.matches("/admin/keys/[a-f0-9-]{36}/revoke") && method.equals("POST")) {
            ledger.revoke(path.split("/")[3]);reply(res,200,Map.of("revoked",true));return;
        }
        throw new RelayError(404,"not_found");
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
