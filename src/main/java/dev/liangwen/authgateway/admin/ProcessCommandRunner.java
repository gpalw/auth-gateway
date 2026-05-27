package dev.liangwen.authgateway.admin;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class ProcessCommandRunner implements CommandRunner {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Override
    public CommandResult run(List<String> command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).start();
            boolean finished = process.waitFor(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new CommandResult(124, "", "command timed out: " + String.join(" ", command));
            }
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            return new CommandResult(process.exitValue(), stdout, stderr);
        } catch (Exception ex) {
            if (process != null) {
                process.destroyForcibly();
            }
            return new CommandResult(127, "", ex.getMessage());
        }
    }
}
