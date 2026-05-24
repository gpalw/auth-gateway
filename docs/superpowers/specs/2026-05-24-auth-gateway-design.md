# Auth Gateway Design

## Goal

Build a Java based identity gateway that replaces repeated Google login code across the user's platforms.

The gateway is an authentication and identity portal, not a full traffic API gateway. Each business platform keeps its own pages and APIs. When a platform sees an unauthenticated user, it redirects to the auth gateway, then receives the user back through standard OAuth2/OpenID Connect.

## Scope For V1

- Spring Boot auth server.
- Google login as the first external identity provider.
- OpenID Connect authorization server for downstream platforms.
- Internal user identity independent of Google provider identity.
- Portal page listing registered applications.
- `/api/me` for the logged-in user.
- Development configuration that runs locally without committing secrets.
- Documentation for Google OAuth credentials and downstream app integration.

Out of scope for V1:

- Full API reverse proxying.
- Business API aggregation.
- Billing.
- Organization/team management.
- MFA and password login.
- A polished admin console for runtime app registration.

## Architecture

```text
Browser
  |
  | visits business app
  v
job-crm / interview / other platforms
  |
  | not logged in
  v
auth-gateway
  |
  | Google OAuth login
  v
Google
  |
  | identity callback
  v
auth-gateway
  |
  | OIDC code/token/session back to platform
  v
business platform
```

The auth gateway owns authentication, account binding, and issued identity. Business platforms own their traffic, pages, and domain APIs.

## Components

### auth-server

Spring Boot application with:

- Spring Authorization Server for OAuth2/OIDC.
- Spring Security OAuth2 Client for Google login.
- Spring MVC plus Thymeleaf for a simple portal UI.
- Spring Data JPA for users and identity links.
- H2 for local development.
- PostgreSQL driver for production deployment.

### Java platform adapter

V1 will document the standard Spring Security resource-server/OIDC-client setup. A dedicated starter JAR can be added after the first gateway is running and one real platform has been integrated.

This avoids overbuilding a library before the protocol contract is proven.

### Node/Python platform adapters

V1 will use standard OIDC metadata:

- issuer: the auth gateway base URL.
- authorization endpoint.
- token endpoint.
- JWKS endpoint.
- userinfo endpoint.

Node and Python services should not need Java-specific code.

## Identity Model

The gateway issues its own stable internal user id. Google's `sub` is stored as an external account identifier, not used as the platform-wide user id.

Core user fields:

- `id`
- `email`
- `displayName`
- `avatarUrl`
- `createdAt`
- `lastLoginAt`

External account fields:

- `provider`
- `providerSubject`
- `email`
- `linkedAt`

For V1 there is only one provider: `google`.

## Token Claims

Downstream platforms receive standard OIDC identity plus gateway-specific claims:

- `sub`: internal gateway user id.
- `email`
- `name`
- `picture`
- `user_id`: same value as `sub` for convenience.

The internal user id is the stable key platforms should store.

## Platform Login Flow

1. User opens a business platform.
2. Platform checks its own session.
3. If unauthenticated, platform starts OIDC login with the auth gateway.
4. Auth gateway signs in the user through Google if needed.
5. Auth gateway creates or updates the internal user record.
6. Auth gateway redirects back to the platform with an authorization code.
7. Platform exchanges the code for tokens.
8. Platform creates its own local session and uses the internal user id from `sub`.

## Portal Flow

The auth gateway root page is the navigation portal.

If the user is not logged in, it shows Google sign-in. If logged in, it shows:

- current user identity,
- configured platform links,
- sign-out.

## Error Handling

- Missing Google credentials should fail clearly at login time, not hide behind a blank page.
- Unknown OIDC clients should receive a standard OAuth error.
- Disabled apps should not appear in the portal.
- `/api/me` should return `401` for unauthenticated users.
- Health checks should not require login.

## Configuration

Secrets must come from environment variables:

- `GOOGLE_CLIENT_ID`
- `GOOGLE_CLIENT_SECRET`

Platform client secrets must also come from environment variables or deployment secrets.

Committed configuration may include local development defaults, but no real credentials.

## Testing

Unit and slice tests should cover:

- creating a user from a Google login,
- updating an existing user on repeat login,
- preserving internal id across repeat provider login,
- portal app filtering,
- unauthenticated `/api/me` behavior,
- authenticated `/api/me` shape,
- token customizer adds gateway claims.

Integration smoke checks should cover:

- application starts,
- `/actuator/health` returns healthy,
- portal renders,
- OAuth metadata endpoint renders,
- no secrets are committed.

