package dev.liangwen.authgateway.config;

import com.nimbusds.jose.jwk.RSAKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

final class SigningKeyLoader {

    private static final Logger log = LoggerFactory.getLogger(SigningKeyLoader.class);

    private SigningKeyLoader() {
    }

    static RSAKey loadOrGenerate(IdentityProperties.SigningKey signingKey) {
        if (signingKey != null && StringUtils.hasText(signingKey.privateKey())) {
            return loadFromPem(signingKey.privateKey(), signingKey.keyId());
        }
        if (signingKey != null && StringUtils.hasText(signingKey.privateKeyLocation())) {
            return loadFromPath(signingKey.privateKeyLocation(), signingKey.keyId());
        }

        log.warn("No persistent JWT signing key configured; generating an ephemeral development RSA key");
        return generateRsa(signingKey == null ? null : signingKey.keyId());
    }

    private static RSAKey loadFromPath(String location, String configuredKeyId) {
        try {
            return loadFromPem(Files.readString(Path.of(location)), configuredKeyId);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to load JWT signing key from " + location, ex);
        }
    }

    private static RSAKey loadFromPem(String privateKeyPem, String configuredKeyId) {
        try {
            byte[] decoded = Base64.getMimeDecoder().decode(cleanPem(privateKeyPem));
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            RSAPrivateKey privateKey = (RSAPrivateKey) keyFactory.generatePrivate(new PKCS8EncodedKeySpec(decoded));
            if (!(privateKey instanceof RSAPrivateCrtKey crtKey)) {
                throw new IllegalArgumentException("RSA private key must include CRT parameters");
            }

            RSAPublicKey publicKey = (RSAPublicKey) keyFactory.generatePublic(
                    new RSAPublicKeySpec(crtKey.getModulus(), crtKey.getPublicExponent()));
            String keyId = StringUtils.hasText(configuredKeyId) ? configuredKeyId : stableKeyId(publicKey);

            return new RSAKey.Builder(publicKey)
                    .privateKey(privateKey)
                    .keyID(keyId)
                    .build();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse JWT signing private key", ex);
        }
    }

    private static RSAKey generateRsa(String configuredKeyId) {
        KeyPair keyPair = generateRsaKeyPair();
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();
        String keyId = StringUtils.hasText(configuredKeyId) ? configuredKeyId : UUID.randomUUID().toString();
        return new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyID(keyId)
                .build();
    }

    private static KeyPair generateRsaKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to generate RSA key pair", ex);
        }
    }

    private static String stableKeyId(RSAPublicKey publicKey) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(publicKey.getEncoded());
        return HexFormat.of().formatHex(digest).substring(0, 16);
    }

    private static String cleanPem(String pem) {
        return pem.replace("\\n", "\n")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
    }
}
