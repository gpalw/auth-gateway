package dev.liangwen.authgateway.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DeploySystemdScriptTest {

    @Test
    void deployScriptInstallsOperationsScriptsInReleaseDirectory() throws Exception {
        String script = Files.readString(Path.of("scripts/deploy-systemd.sh"));

        assertThat(script)
                .contains("if [ -f \"$SCRIPT_DIR/prod-check.sh\" ]; then")
                .contains("install -m 0755 \"$SCRIPT_DIR/prod-check.sh\" \"$RELEASE_DIR/prod-check.sh\"")
                .contains("if [ -f \"$SCRIPT_DIR/h2-to-postgres-dry-run.sh\" ]; then")
                .contains("install -m 0755 \"$SCRIPT_DIR/h2-to-postgres-dry-run.sh\" \"$RELEASE_DIR/h2-to-postgres-dry-run.sh\"");
    }
}
