package dev.liangwen.relay;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@DependsOn("relayMigrations")
final class Ledger {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final Secrets secrets;
    private final RelaySettings settings;
    private final Clock clock;
    Ledger(JdbcTemplate db, org.springframework.transaction.PlatformTransactionManager tm, Secrets secrets, RelaySettings settings, Clock clock) {
        this.db=db; this.tx=new TransactionTemplate(tm); this.secrets=secrets; this.settings=settings; this.clock=clock;
        for(var p:settings.prices()) {
            try {db.update("INSERT INTO relay_price(model,version,input_per_million,cached_per_million,output_per_million,max_input,max_output) VALUES(?,?,?,?,?,?,?)",
                p.model(),p.version(),p.inputPerMillion(),p.cachedPerMillion(),p.outputPerMillion(),p.maxInput(),p.maxOutput());}
            catch(org.springframework.dao.DuplicateKeyException alreadyRegistered) { /* Another worker may register the same immutable schedule. */ }
            Map<String,Object> stored=db.queryForMap("SELECT * FROM relay_price WHERE model=? AND version=?",p.model(),p.version());
            if(((BigDecimal)stored.get("input_per_million")).compareTo(p.inputPerMillion())!=0
                || ((BigDecimal)stored.get("cached_per_million")).compareTo(p.cachedPerMillion())!=0
                || ((BigDecimal)stored.get("output_per_million")).compareTo(p.outputPerMillion())!=0
                || ((Number)stored.get("max_input")).intValue()!=p.maxInput() || ((Number)stored.get("max_output")).intValue()!=p.maxOutput())
                throw new IllegalArgumentException("A price version cannot be changed; register a new version");
        }
    }
    record Key(String id,String hash,boolean revoked,Set<String> models,BigDecimal monthly,BigDecimal total,
               Instant expires,int rpm,int maxConcurrent,List<String> cidrs,long rateMinute,int rateCount) {}
    record Policy(Instant expires,int rpm,int maxConcurrent,List<String> cidrs) {
        static Policy defaults(){return new Policy(null,60,8,List.of());}
    }
    record Reservation(String id,String keyId,String model,String endpoint,RelaySettings.Price price,BigDecimal maximum,int inputBound,int outputBound) {}
    Map<String,Object> create(String name,Set<String> models,BigDecimal monthly,BigDecimal total) {
        return create(name,models,monthly,total,Policy.defaults());
    }
    Map<String,Object> create(String name,Set<String> models,BigDecimal monthly,BigDecimal total,Policy policy) {
        if (name==null || !name.matches("[a-zA-Z0-9 _.-]{1,80}") || models==null || models.isEmpty() || models.size()>20) throw new RelayError(400,"invalid_key_policy");
        models.forEach(settings::price); money(monthly); money(total);
        validatePolicy(policy);
        String id=UUID.randomUUID().toString(), token=secrets.token(id), prefix="sk-lw-"+id;
        tx.executeWithoutResult(s->{
            db.update("INSERT INTO relay_key(id,name,prefix,secret_hash,models,monthly_budget,total_budget,created_at,expires_at,rpm,max_concurrent,source_cidrs) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                id,name,prefix,secrets.hash(token),String.join(",",new TreeSet<>(models)),monthly,total,clock.instant().toString(),
                policy.expires()==null?null:policy.expires().toString(),policy.rpm(),policy.maxConcurrent(),String.join(",",policy.cidrs()));
            keyEvent(id,"CREATED");
        });
        return Map.of("id",id,"prefix",prefix,"token",token);
    }
    private void validatePolicy(Policy p) {
        if(p==null || p.rpm()<1 || p.rpm()>6000 || p.maxConcurrent()<1 || p.maxConcurrent()>64 || p.cidrs()==null || p.cidrs().size()>20
                || p.expires()!=null && !p.expires().isAfter(clock.instant())) throw new RelayError(400,"invalid_key_policy");
        p.cidrs().forEach(SourcePolicy::validate);
    }
    static void money(BigDecimal b) {
        if (b==null || b.signum()<0 || b.scale()>10 || b.compareTo(new BigDecimal("1000000"))>0) throw new RelayError(400,"invalid_money");
    }
    private Key row(java.sql.ResultSet r) throws java.sql.SQLException {
        String expires=r.getString("expires_at"),sources=r.getString("source_cidrs");
        return new Key(r.getString("id"),r.getString("secret_hash"),r.getBoolean("revoked"),Set.of(r.getString("models").split(",")),r.getBigDecimal("monthly_budget"),r.getBigDecimal("total_budget"),
            expires==null?null:Instant.parse(expires),r.getInt("rpm"),r.getInt("max_concurrent"),sources.isEmpty()?List.of():List.of(sources.split(",")),r.getLong("rate_minute"),r.getInt("rate_count"));
    }
    Key authenticate(String token, boolean lock) {
        if (!token.matches("sk-lw-[a-f0-9-]{36}_[A-Za-z0-9_-]{43}")) throw new RelayError(401,"invalid_api_key");
        String id=token.substring(6,42);
        List<Key> keys=db.query("SELECT * FROM relay_key WHERE id=?"+(lock?" FOR UPDATE":""),(r,n)->row(r),id);
        if (keys.isEmpty() || !Secrets.same(keys.getFirst().hash(),secrets.hash(token)) || keys.getFirst().revoked()
                || keys.getFirst().expires()!=null && !keys.getFirst().expires().isAfter(clock.instant())) throw new RelayError(401,"invalid_api_key");
        return keys.getFirst();
    }
    Reservation reserve(String token,String model,String endpoint,int inputBound,int outputBound) {
        return reserve(token,model,endpoint,inputBound,outputBound,"127.0.0.1",null);
    }
    Key authorize(String token,String source) {
        Key k=authenticate(token,false);checkSource(k,source);return k;
    }
    private static void checkSource(Key k,String source) {
        if(!k.cidrs().isEmpty() && k.cidrs().stream().noneMatch(c->SourcePolicy.permits(source,c)))throw new RelayError(403,"source_not_allowed");
    }
    Reservation reserve(String token,String model,String endpoint,int inputBound,int outputBound,String source,String idempotency) {
        return tx.execute(status->{
            Key k=authenticate(token,true);
            checkSource(k,source);
            String retryHash=idempotency==null?null:secrets.hash("retry:"+idempotency);
            if(retryHash!=null && db.queryForObject("SELECT COUNT(*) FROM relay_request WHERE key_id=? AND idempotency_hash=?",Integer.class,k.id(),retryHash)>0)
                throw new RelayError(409,"idempotency_key_already_used");
            if (!k.models().contains(model)) throw new RelayError(403,"model_not_allowed");
            RelaySettings.Price p=settings.price(model);
            if(inputBound<1 || inputBound>p.maxInput() || outputBound<1 || outputBound>p.maxOutput())throw new RelayError(400,"invalid_limits");
            long minute=Math.floorDiv(clock.instant().getEpochSecond(),60);
            int count=k.rateMinute()==minute?k.rateCount():0;
            if(count>=k.rpm())throw new RelayError(429,"gateway_rate_exceeded");
            if(db.queryForObject("SELECT COUNT(*) FROM relay_request WHERE key_id=? AND status='RESERVED'",Integer.class,k.id())>=k.maxConcurrent())
                throw new RelayError(429,"gateway_concurrency_exceeded");
            BigDecimal maximum=p.cost(inputBound,outputBound,0);
            String month=YearMonth.from(clock.instant().atOffset(ZoneOffset.UTC)).toString();
            BigDecimal total=db.queryForObject("SELECT COALESCE(SUM(cost+held),0) FROM relay_request WHERE key_id=?",BigDecimal.class,k.id());
            BigDecimal monthly=db.queryForObject("SELECT COALESCE(SUM(cost+held),0) FROM relay_request WHERE key_id=? AND charge_month=?",BigDecimal.class,k.id(),month);
            if (total.add(maximum).compareTo(k.total())>0 || monthly.add(maximum).compareTo(k.monthly())>0) throw new RelayError(429,"gateway_budget_exceeded");
            String id=UUID.randomUUID().toString(), now=clock.instant().toString();
            db.update("INSERT INTO relay_request(id,key_id,model,endpoint,charge_month,price_version,reservation,held,status,created_at,updated_at,idempotency_hash) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                id,k.id(),model,endpoint,month,p.version(),maximum,maximum,"RESERVED",now,now,retryHash);
            db.update("UPDATE relay_key SET rate_minute=?,rate_count=? WHERE id=?",minute,count+1,k.id());
            event(id,"RESERVED",BigDecimal.ZERO,maximum);
            return new Reservation(id,k.id(),model,endpoint,p,maximum,inputBound,outputBound);
        });
    }
    void finish(Reservation r,Long input,Long output,Long cached,String upstreamId,boolean knownFree) {
        finish(r,input,output,cached,upstreamId,knownFree,null);
    }
    void finish(Reservation r,Long input,Long output,Long cached,String upstreamId,boolean knownFree,String category) {
        tx.executeWithoutResult(status->{
            db.queryForObject("SELECT id FROM relay_key WHERE id=? FOR UPDATE",String.class,r.keyId());
            String state=db.queryForObject("SELECT status FROM relay_request WHERE id=?",String.class,r.id());
            if (!"RESERVED".equals(state)) return; // idempotent settlement
            boolean valid=input!=null && output!=null && cached!=null && input>=0 && output>=0 && cached>=0 && cached<=input
                && input<=r.inputBound() && output<=r.outputBound();
            BigDecimal cost=knownFree?BigDecimal.ZERO:(valid?r.price().cost(input,output,cached):BigDecimal.ZERO);
            BigDecimal held=knownFree || valid ? BigDecimal.ZERO:r.maximum();
            String next=knownFree?"REJECTED":valid?"SETTLED":"UNRESOLVED";
            // Provider values outside the reserved bounds are an accounting anomaly, never silently free.
            String safeId=upstreamId!=null && upstreamId.matches("req_[A-Za-z0-9_-]{1,80}")?upstreamId:null;
            String error=valid?null:category==null?"missing_or_invalid_usage":category;
            if(error!=null && !error.matches("[a-z0-9_]{1,48}"))throw new IllegalArgumentException("Unsafe error category");
            db.update("UPDATE relay_request SET cost=?,held=?,status=?,input_tokens=?,output_tokens=?,cached_tokens=?,upstream_id=?,updated_at=?,error_category=? WHERE id=?",
                cost,held,next,valid?input:null,valid?output:null,valid?cached:null,safeId,clock.instant().toString(),error,r.id());
            event(r.id(),next,cost,held,error);
        });
    }
    private void event(String requestId,String kind,BigDecimal cost,BigDecimal held) {
        event(requestId,kind,cost,held,null);
    }
    private void event(String requestId,String kind,BigDecimal cost,BigDecimal held,String error) {
        db.update("INSERT INTO relay_event(id,request_id,kind,cost,held,created_at,error_category) VALUES(?,?,?,?,?,?,?)",UUID.randomUUID().toString(),requestId,kind,cost,held,clock.instant().toString(),error);
    }
    void revoke(String id) {
        tx.executeWithoutResult(s->{
            if (db.query("SELECT id FROM relay_key WHERE id=? FOR UPDATE",(r,n)->r.getString(1),id).isEmpty()) throw new RelayError(404,"key_not_found");
            db.update("UPDATE relay_key SET revoked=TRUE WHERE id=?",id);
            keyEvent(id,"REVOKED");
        });
    }
    Map<String,Object> rotate(String id) {
        return tx.execute(s->{
            List<Key> keys=db.query("SELECT * FROM relay_key WHERE id=? FOR UPDATE",(r,n)->row(r),id);
            if(keys.isEmpty() || keys.getFirst().revoked())throw new RelayError(404,"key_not_found");
            String token=secrets.token(id);db.update("UPDATE relay_key SET secret_hash=? WHERE id=?",secrets.hash(token),id);keyEvent(id,"ROTATED");
            return Map.of("id",id,"prefix","sk-lw-"+id,"token",token);
        });
    }
    void policy(String id,Set<String> models,BigDecimal monthly,BigDecimal total,Policy p) {
        validatePolicy(p);money(monthly);money(total);if(models.isEmpty() || models.size()>20)throw new RelayError(400,"invalid_models");models.forEach(settings::price);
        tx.executeWithoutResult(s->{
            if(db.query("SELECT id FROM relay_key WHERE id=? FOR UPDATE",(r,n)->r.getString(1),id).isEmpty())throw new RelayError(404,"key_not_found");
            db.update("UPDATE relay_key SET models=?,monthly_budget=?,total_budget=?,expires_at=?,rpm=?,max_concurrent=?,source_cidrs=? WHERE id=?",
                String.join(",",new TreeSet<>(models)),monthly,total,p.expires()==null?null:p.expires().toString(),p.rpm(),p.maxConcurrent(),String.join(",",p.cidrs()),id);
            keyEvent(id,"POLICY_UPDATED");
        });
    }
    private void keyEvent(String key,String kind){db.update("INSERT INTO relay_key_event(id,key_id,kind,created_at) VALUES(?,?,?,?)",UUID.randomUUID().toString(),key,kind,clock.instant().toString());}
    void reconcile(String id,BigDecimal verifiedCost,String reason) {
        money(verifiedCost);
        if(!Set.of("provider_usage_verified","provider_no_charge_verified").contains(reason))throw new RelayError(400,"invalid_reconciliation_reason");
        if(reason.equals("provider_no_charge_verified") && verifiedCost.signum()!=0)throw new RelayError(400,"invalid_reconciliation_cost");
        tx.executeWithoutResult(s->{
            List<String> keys=db.query("SELECT key_id FROM relay_request WHERE id=?",(r,n)->r.getString(1),id);
            if(keys.isEmpty())throw new RelayError(404,"request_not_found");
            db.queryForObject("SELECT id FROM relay_key WHERE id=? FOR UPDATE",String.class,keys.getFirst());
            Map<String,Object> row=db.queryForMap("SELECT status,reservation,created_at FROM relay_request WHERE id=?",id);
            String state=String.valueOf(row.get("status"));
            if(state.equals("RESERVED") && Instant.parse(String.valueOf(row.get("created_at"))).plusSeconds(settings.timeoutSeconds()*3L+60).isBefore(clock.instant())){
                db.update("UPDATE relay_request SET status='UNRESOLVED',updated_at=? WHERE id=?",clock.instant().toString(),id);
                event(id,"STALE_HOLD",BigDecimal.ZERO,(BigDecimal)row.get("reservation"));state="UNRESOLVED";
            }
            if(!state.equals("UNRESOLVED"))throw new RelayError(409,"request_not_reconcilable");
            // Provider-verified costs may exceed a reservation; record the actual liability and block future admission.
            db.update("UPDATE relay_request SET cost=?,held=0,status='RECONCILED',updated_at=? WHERE id=?",verifiedCost,clock.instant().toString(),id);
            event(id,reason.toUpperCase(Locale.ROOT),verifiedCost,BigDecimal.ZERO);
        });
    }
    List<Map<String,Object>> keys() { return db.queryForList("SELECT id,name,prefix,revoked,models,monthly_budget,total_budget,expires_at,rpm,max_concurrent,source_cidrs,created_at FROM relay_key ORDER BY created_at"); }
    List<Map<String,Object>> requests() { return db.queryForList("SELECT id,key_id,model,endpoint,charge_month,price_version,reservation,cost,held,status,input_tokens,output_tokens,cached_tokens,upstream_id,error_category,created_at,updated_at FROM relay_request ORDER BY created_at DESC LIMIT 200"); }
    Map<String,Object> usage() {
        return Map.of("requests",requests(),"daily",db.queryForList("SELECT key_id,SUBSTRING(created_at,1,10) AS usage_day,COUNT(*) AS requests,SUM(cost) AS cost,SUM(held) AS held,SUM(input_tokens) AS input_tokens,SUM(output_tokens) AS output_tokens,SUM(cached_tokens) AS cached_tokens FROM relay_request GROUP BY key_id,SUBSTRING(created_at,1,10)"),
            "monthly",db.queryForList("SELECT key_id,charge_month,COUNT(*) AS requests,SUM(cost) AS cost,SUM(held) AS held FROM relay_request GROUP BY key_id,charge_month"),
            "outcomes",db.queryForList("SELECT key_id,status,error_category,COUNT(*) AS requests FROM relay_request GROUP BY key_id,status,error_category"));
    }
    boolean healthy(){try{return Integer.valueOf(1).equals(db.queryForObject("SELECT 1",Integer.class));}catch(Exception e){return false;}}
}
