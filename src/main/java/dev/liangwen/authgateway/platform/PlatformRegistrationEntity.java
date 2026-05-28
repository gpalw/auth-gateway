package dev.liangwen.authgateway.platform;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "platform_registrations")
class PlatformRegistrationEntity {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, unique = true)
    private String clientId;

    @Column(nullable = false, length = 512)
    private String clientSecret;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, length = 1024)
    private String description;

    @Column(nullable = false, length = 2048)
    private String homeUrl;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "platform_redirect_uris", joinColumns = @JoinColumn(name = "platform_id"))
    @Column(name = "redirect_uri", nullable = false, length = 2048)
    private List<String> redirectUris = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "platform_logout_redirect_uris", joinColumns = @JoinColumn(name = "platform_id"))
    @Column(name = "redirect_uri", nullable = false, length = 2048)
    private List<String> postLogoutRedirectUris = new ArrayList<>();

    @Column(nullable = false)
    private boolean enabled;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected PlatformRegistrationEntity() {
    }

    static PlatformRegistrationEntity from(PlatformRegistration registration) {
        PlatformRegistrationEntity entity = new PlatformRegistrationEntity();
        entity.id = registration.id();
        entity.apply(registration);
        Instant now = Instant.now();
        entity.createdAt = now;
        entity.updatedAt = now;
        return entity;
    }

    void apply(PlatformRegistration registration) {
        this.clientId = registration.clientId();
        this.clientSecret = registration.clientSecret();
        this.name = registration.name();
        this.description = registration.description();
        this.homeUrl = registration.homeUrl();
        this.redirectUris = new ArrayList<>(registration.redirectUris());
        this.postLogoutRedirectUris = new ArrayList<>(registration.postLogoutRedirectUris());
        this.enabled = registration.enabled();
        this.updatedAt = Instant.now();
    }

    PlatformRegistration toRegistration() {
        return new PlatformRegistration(
                id,
                clientId,
                clientSecret,
                name,
                description,
                homeUrl,
                redirectUris,
                postLogoutRedirectUris,
                enabled);
    }
}
