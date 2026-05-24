package dev.liangwen.authgateway.user;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

public final class GatewayOidcUser implements OidcUser, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final GatewayUser gatewayUser;
    private final OidcUser delegate;
    private final Map<String, Object> claims;

    private GatewayOidcUser(GatewayUser gatewayUser, OidcUser delegate) {
        this.gatewayUser = gatewayUser;
        this.delegate = delegate;
        this.claims = gatewayClaims(gatewayUser, delegate);
    }

    public static GatewayOidcUser from(GatewayUser gatewayUser, OidcUser delegate) {
        return new GatewayOidcUser(gatewayUser, delegate);
    }

    public GatewayUser gatewayUser() {
        return gatewayUser;
    }

    @Override
    public Map<String, Object> getClaims() {
        return claims;
    }

    @Override
    public OidcUserInfo getUserInfo() {
        return delegate.getUserInfo();
    }

    @Override
    public OidcIdToken getIdToken() {
        return delegate.getIdToken();
    }

    @Override
    public Map<String, Object> getAttributes() {
        return claims;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return delegate.getAuthorities();
    }

    @Override
    public String getName() {
        return gatewayUser.id().toString();
    }

    private static Map<String, Object> gatewayClaims(GatewayUser user, OidcUser delegate) {
        Map<String, Object> merged = new LinkedHashMap<>(delegate.getClaims());
        String userId = user.id().toString();
        merged.put("sub", userId);
        merged.put("user_id", userId);
        merged.put("email", user.email());
        merged.put("name", user.displayName());
        if (user.avatarUrl() != null) {
            merged.put("picture", user.avatarUrl());
        }
        return Map.copyOf(merged);
    }
}
