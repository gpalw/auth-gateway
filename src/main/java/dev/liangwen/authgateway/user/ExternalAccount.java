package dev.liangwen.authgateway.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "external_accounts",
        uniqueConstraints = @UniqueConstraint(name = "uk_external_account_provider_subject", columnNames = {
                "provider", "providerSubject"
        }))
public class ExternalAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(nullable = false)
    private String provider;

    @Column(nullable = false)
    private String providerSubject;

    @Column(nullable = false)
    private String email;

    @Column(nullable = false)
    private Instant linkedAt;

    @Column(nullable = false)
    private Instant lastLoginAt;

    protected ExternalAccount() {
    }

    public ExternalAccount(AppUser user, String provider, String providerSubject, String email, Instant now) {
        this.user = user;
        this.provider = provider;
        this.providerSubject = providerSubject;
        this.email = email;
        this.linkedAt = now;
        this.lastLoginAt = now;
    }

    public void recordLogin(String email, Instant now) {
        this.email = email;
        this.lastLoginAt = now;
    }

    public UUID getId() {
        return id;
    }

    public AppUser getUser() {
        return user;
    }

    public String getProvider() {
        return provider;
    }

    public String getProviderSubject() {
        return providerSubject;
    }

    public String getEmail() {
        return email;
    }

    public Instant getLinkedAt() {
        return linkedAt;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }
}
