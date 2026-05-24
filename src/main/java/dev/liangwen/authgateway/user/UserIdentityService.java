package dev.liangwen.authgateway.user;

import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class UserIdentityService {

    private static final String GOOGLE_PROVIDER = "google";

    private final AppUserRepository users;
    private final ExternalAccountRepository externalAccounts;

    public UserIdentityService(AppUserRepository users, ExternalAccountRepository externalAccounts) {
        this.users = users;
        this.externalAccounts = externalAccounts;
    }

    @Transactional
    public GatewayUser upsertGoogleUser(String providerSubject, String email, String displayName, String avatarUrl) {
        String normalizedEmail = requireText(email, "email");
        String normalizedSubject = requireText(providerSubject, "providerSubject");
        String effectiveDisplayName = StringUtils.hasText(displayName) ? displayName : normalizedEmail;
        Instant now = Instant.now();

        return externalAccounts.findByProviderAndProviderSubject(GOOGLE_PROVIDER, normalizedSubject)
                .map(account -> updateExistingAccount(account, normalizedEmail, effectiveDisplayName, avatarUrl, now))
                .orElseGet(() -> createAccount(normalizedSubject, normalizedEmail, effectiveDisplayName, avatarUrl, now));
    }

    private GatewayUser updateExistingAccount(
            ExternalAccount account,
            String email,
            String displayName,
            String avatarUrl,
            Instant now) {
        AppUser user = account.getUser();
        user.recordLogin(email, displayName, avatarUrl, now);
        account.recordLogin(email, now);
        return GatewayUser.from(user);
    }

    private GatewayUser createAccount(
            String providerSubject,
            String email,
            String displayName,
            String avatarUrl,
            Instant now) {
        AppUser user = users.save(new AppUser(email, displayName, avatarUrl, now));
        externalAccounts.save(new ExternalAccount(user, GOOGLE_PROVIDER, providerSubject, email, now));
        return GatewayUser.from(user);
    }

    private static String requireText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
