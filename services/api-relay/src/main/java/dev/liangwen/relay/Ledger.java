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
    }
    record Key(String id,String hash,boolean revoked,Set<String> models,BigDecimal monthly,BigDecimal total) {}
    record Reservation(String id,String keyId,String model,String endpoint,RelaySettings.Price price,BigDecimal maximum,int inputBound,int outputBound) {}
    Map<String,Object> create(String name,Set<String> models,BigDecimal monthly,BigDecimal total) {
        if (name==null || !name.matches("[a-zA-Z0-9 _.-]{1,80}") || models==null || models.isEmpty() || models.size()>20) throw new RelayError(400,"invalid_key_policy");
        models.forEach(settings::price); money(monthly); money(total);
        String id=UUID.randomUUID().toString(), token=secrets.token(id), prefix="sk-lw-"+id;
        db.update("INSERT INTO relay_key(id,name,prefix,secret_hash,models,monthly_budget,total_budget,created_at) VALUES(?,?,?,?,?,?,?,?)",
            id,name,prefix,secrets.hash(token),String.join(",",new TreeSet<>(models)),monthly,total,clock.instant().toString());
        return Map.of("id",id,"prefix",prefix,"token",token);
    }
    static void money(BigDecimal b) {
        if (b==null || b.signum()<0 || b.scale()>10 || b.compareTo(new BigDecimal("1000000"))>0) throw new RelayError(400,"invalid_money");
    }
    private Key row(java.sql.ResultSet r) throws java.sql.SQLException {
        return new Key(r.getString("id"),r.getString("secret_hash"),r.getBoolean("revoked"),Set.of(r.getString("models").split(",")),r.getBigDecimal("monthly_budget"),r.getBigDecimal("total_budget"));
    }
    Key authenticate(String token, boolean lock) {
        if (!token.matches("sk-lw-[a-f0-9-]{36}_[A-Za-z0-9_-]{43}")) throw new RelayError(401,"invalid_api_key");
        String id=token.substring(6,42);
        List<Key> keys=db.query("SELECT * FROM relay_key WHERE id=?"+(lock?" FOR UPDATE":""),(r,n)->row(r),id);
        if (keys.isEmpty() || !Secrets.same(keys.getFirst().hash(),secrets.hash(token)) || keys.getFirst().revoked()) throw new RelayError(401,"invalid_api_key");
        return keys.getFirst();
    }
    Reservation reserve(String token,String model,String endpoint,int inputBound,int outputBound) {
        return tx.execute(status->{
            Key k=authenticate(token,true);
            if (!k.models().contains(model)) throw new RelayError(403,"model_not_allowed");
            RelaySettings.Price p=settings.price(model);
            BigDecimal maximum=p.cost(inputBound,outputBound,0);
            String month=YearMonth.from(clock.instant().atOffset(ZoneOffset.UTC)).toString();
            BigDecimal total=db.queryForObject("SELECT COALESCE(SUM(cost+held),0) FROM relay_request WHERE key_id=?",BigDecimal.class,k.id());
            BigDecimal monthly=db.queryForObject("SELECT COALESCE(SUM(cost+held),0) FROM relay_request WHERE key_id=? AND charge_month=?",BigDecimal.class,k.id(),month);
            if (total.add(maximum).compareTo(k.total())>0 || monthly.add(maximum).compareTo(k.monthly())>0) throw new RelayError(429,"gateway_budget_exceeded");
            String id=UUID.randomUUID().toString(), now=clock.instant().toString();
            db.update("INSERT INTO relay_request(id,key_id,model,endpoint,charge_month,price_version,reservation,held,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                id,k.id(),model,endpoint,month,p.version(),maximum,maximum,"RESERVED",now,now);
            event(id,"RESERVED",BigDecimal.ZERO,maximum);
            return new Reservation(id,k.id(),model,endpoint,p,maximum,inputBound,outputBound);
        });
    }
    void finish(Reservation r,Long input,Long output,Long cached,String upstreamId,boolean knownFree) {
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
            String safeId=upstreamId!=null && upstreamId.matches("[A-Za-z0-9_-]{1,100}")?upstreamId:null;
            db.update("UPDATE relay_request SET cost=?,held=?,status=?,input_tokens=?,output_tokens=?,cached_tokens=?,upstream_id=?,updated_at=? WHERE id=?",
                cost,held,next,valid?input:null,valid?output:null,valid?cached:null,safeId,clock.instant().toString(),r.id());
            event(r.id(),next,cost,held);
        });
    }
    private void event(String requestId,String kind,BigDecimal cost,BigDecimal held) {
        db.update("INSERT INTO relay_event(id,request_id,kind,cost,held,created_at) VALUES(?,?,?,?,?,?)",UUID.randomUUID().toString(),requestId,kind,cost,held,clock.instant().toString());
    }
    void revoke(String id) {
        tx.executeWithoutResult(s->{
            if (db.query("SELECT id FROM relay_key WHERE id=? FOR UPDATE",(r,n)->r.getString(1),id).isEmpty()) throw new RelayError(404,"key_not_found");
            db.update("UPDATE relay_key SET revoked=TRUE WHERE id=?",id);
        });
    }
    List<Map<String,Object>> keys() { return db.queryForList("SELECT id,name,prefix,revoked,models,monthly_budget,total_budget,created_at FROM relay_key ORDER BY created_at"); }
    List<Map<String,Object>> requests() { return db.queryForList("SELECT * FROM relay_request ORDER BY created_at DESC LIMIT 200"); }
}
