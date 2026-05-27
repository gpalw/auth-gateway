package dev.liangwen.authgateway.admin;

import java.util.List;

public record ServiceInventoryItem(
        String id,
        String name,
        ServiceSource source,
        ServiceStatus status,
        String url,
        String listenAddress,
        Integer port,
        AuthType authType,
        String oidcClientId,
        List<String> redirectUris,
        String description,
        String raw) {

    public ServiceInventoryItem {
        redirectUris = redirectUris == null ? List.of() : List.copyOf(redirectUris);
    }
}
