package dev.liangwen.authgateway.admin;

public record CommandResult(int exitCode, String stdout, String stderr) {
}
