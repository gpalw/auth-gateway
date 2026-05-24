package dev.liangwen.authgateway.user;

import java.util.UUID;

public record GatewayUser(UUID id, String email, String displayName, String avatarUrl) {

    static GatewayUser from(AppUser user) {
        return new GatewayUser(user.getId(), user.getEmail(), user.getDisplayName(), user.getAvatarUrl());
    }
}
