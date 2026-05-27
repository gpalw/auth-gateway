package dev.liangwen.authgateway.admin;

import java.util.List;

@FunctionalInterface
public interface CommandRunner {

    CommandResult run(List<String> command);
}
