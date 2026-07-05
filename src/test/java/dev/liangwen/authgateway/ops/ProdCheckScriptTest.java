package dev.liangwen.authgateway.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ProdCheckScriptTest {

    @Test
    void scriptDoesNotContainDirectSecretEchoes() throws Exception {
        String script = Files.readString(Path.of("scripts/prod-check.sh"));

        assertThat(script)
                .doesNotContain("echo \"$GOOGLE_CLIENT_SECRET\"")
                .doesNotContain("echo \"$DATABASE_PASSWORD\"")
                .doesNotContain("echo \"$AUTH_GATEWAY_JWT_PRIVATE_KEY\"")
                .doesNotContain("cat \"$AUTH_GATEWAY_JWT_PRIVATE_KEY_PATH\"");
    }

    @Test
    void scriptChecksLikelyNginxConfigLocations() throws Exception {
        String script = Files.readString(Path.of("scripts/prod-check.sh"));

        assertThat(script)
                .contains("/etc/nginx/sites-enabled/auth.liangwendev.com.conf")
                .contains("/etc/nginx/conf.d/auth.liangwendev.com.conf");
    }

    @Test
    void scriptReadsProtectedEnvFileWithNonInteractiveSudo() throws Exception {
        String script = Files.readString(Path.of("scripts/prod-check.sh"));

        assertThat(script)
                .contains("sudo -n awk")
                .contains("sudo -n test -r \"$ENV_FILE\"");
    }

    @Test
    void scriptHasValidBashSyntaxWhenBashIsAvailable() throws Exception {
        assumeTrue(hasBash());

        Process process = new ProcessBuilder("bash", "-n", "scripts/prod-check.sh")
                .redirectErrorStream(true)
                .start();
        boolean exited = process.waitFor(5, TimeUnit.SECONDS);

        assertThat(exited).isTrue();
        assertThat(process.exitValue()).isZero();
    }

    private static boolean hasBash() {
        try {
            Process process = new ProcessBuilder("bash", "--version")
                    .redirectErrorStream(true)
                    .start();
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception ex) {
            return false;
        }
    }
}
