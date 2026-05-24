package dev.liangwen.authgateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.RSAKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SigningKeyLoaderTest {

    @TempDir
    private Path tempDir;

    @Test
    void loadsPkcs8PrivateKeyPemFromInlineConfiguration() throws Exception {
        KeyPair keyPair = generateKeyPair();
        String privateKeyPem = privateKeyPem(keyPair);

        RSAKey rsaKey = SigningKeyLoader.loadOrGenerate(
                new IdentityProperties.SigningKey(null, privateKeyPem, "prod-main"));

        assertThat(rsaKey.getKeyID()).isEqualTo("prod-main");
        assertThat(rsaKey.toRSAPublicKey().getModulus())
                .isEqualTo(((RSAPrivateKey) keyPair.getPrivate()).getModulus());
        assertThat(rsaKey.isPrivate()).isTrue();
    }

    @Test
    void loadsPkcs8PrivateKeyPemFromFileLocation() throws Exception {
        KeyPair keyPair = generateKeyPair();
        Path keyFile = tempDir.resolve("auth-gateway-private-key.pem");
        Files.writeString(keyFile, privateKeyPem(keyPair));

        RSAKey rsaKey = SigningKeyLoader.loadOrGenerate(
                new IdentityProperties.SigningKey(keyFile.toString(), null, "file-key"));

        assertThat(rsaKey.getKeyID()).isEqualTo("file-key");
        assertThat(rsaKey.toRSAPrivateKey().getModulus())
                .isEqualTo(((RSAPrivateKey) keyPair.getPrivate()).getModulus());
    }

    @Test
    void createsStableKeyIdFromConfiguredKeyWhenNoKeyIdIsProvided() throws Exception {
        String privateKeyPem = privateKeyPem(generateKeyPair());

        RSAKey first = SigningKeyLoader.loadOrGenerate(
                new IdentityProperties.SigningKey(null, privateKeyPem, null));
        RSAKey second = SigningKeyLoader.loadOrGenerate(
                new IdentityProperties.SigningKey(null, privateKeyPem, null));

        assertThat(first.getKeyID()).isNotBlank();
        assertThat(second.getKeyID()).isEqualTo(first.getKeyID());
    }

    private static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static String privateKeyPem(KeyPair keyPair) {
        String encoded = Base64.getMimeEncoder(64, System.lineSeparator().getBytes())
                .encodeToString(keyPair.getPrivate().getEncoded());
        return "-----BEGIN PRIVATE KEY-----"
                + System.lineSeparator()
                + encoded
                + System.lineSeparator()
                + "-----END PRIVATE KEY-----"
                + System.lineSeparator();
    }
}
