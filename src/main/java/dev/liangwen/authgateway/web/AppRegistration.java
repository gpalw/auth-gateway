package dev.liangwen.authgateway.web;

import dev.liangwen.authgateway.config.IdentityProperties;
import dev.liangwen.authgateway.platform.PlatformRegistration;

public record AppRegistration(String id, String name, String description, String url) {

    public static AppRegistration from(IdentityProperties.App app) {
        return new AppRegistration(app.id(), app.name(), app.description(), app.url());
    }

    public static AppRegistration from(PlatformRegistration platform) {
        return new AppRegistration(platform.clientId(), platform.name(), platform.description(), platform.homeUrl());
    }
}
