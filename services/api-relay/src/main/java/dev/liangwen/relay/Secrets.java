package dev.liangwen.relay;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
final class Secrets {
    private final byte[] pepper;
    private final SecureRandom random=new SecureRandom();
    Secrets(RelaySettings settings) { pepper=settings.pepper().getBytes(StandardCharsets.UTF_8); }
    String token(String id) { byte[] b=new byte[32]; random.nextBytes(b); return "sk-lw-"+id+"_"+Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    String hash(String token) {
        try { Mac m=Mac.getInstance("HmacSHA256"); m.init(new SecretKeySpec(pepper,"HmacSHA256")); return HexFormat.of().formatHex(m.doFinal(token.getBytes(StandardCharsets.UTF_8))); }
        catch(GeneralSecurityException e) { throw new IllegalStateException("Hash unavailable"); }
    }
    static boolean same(String a, String b) { return a!=null && b!=null && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8)); }
    static String bearer(String header) {
        if (header==null || header.length()>256 || !header.startsWith("Bearer ")) throw new RelayError(401,"invalid_api_key");
        return header.substring(7);
    }
}
