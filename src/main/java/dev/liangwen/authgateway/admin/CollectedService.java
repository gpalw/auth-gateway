package dev.liangwen.authgateway.admin;

public record CollectedService(
        ServiceSource source,
        String name,
        ServiceStatus status,
        String listenAddress,
        Integer port,
        String description,
        String raw) {
}
