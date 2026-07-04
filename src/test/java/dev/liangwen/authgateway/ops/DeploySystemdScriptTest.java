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
                .contains("install_release_script \"prod-check.sh\"")
                .contains("install_release_script \"h2-to-postgres-dry-run.sh\"")
                .contains("local previous_path=\"$INSTALL_DIR/current-release/$script_name\"")
                .contains("install -m 0755 \"$previous_path\" \"$RELEASE_DIR/$script_name\"");
    }
}
