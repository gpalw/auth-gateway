# Auth Gateway

Java identity gateway for the user's platforms. It centralizes Google login and issues standard OpenID Connect identity for downstream apps while each platform keeps its own pages and business APIs.

## What It Does

- Signs users in with Google.
- Creates a stable internal gateway user id.
- Stores Google account links separately from internal users.
- Provides a portal page at `/`.
- Exposes `/api/me` for the current user.
- Acts as an OpenID Connect provider for platforms such as Job CRM and Interview Intelligence.

It is not a full traffic API gateway. Business apps should still serve their own UI and APIs.

## Local Run

Requirements:

- Java 21
- PowerShell or another shell that can run Maven Wrapper

Run tests:

```powershell
.\mvnw.cmd test
```

Start the app:

```powershell
$env:GOOGLE_CLIENT_ID='your-google-client-id'
$env:GOOGLE_CLIENT_SECRET='your-google-client-secret'
$env:AUTH_GATEWAY_ISSUER='http://localhost:8080'
.\mvnw.cmd spring-boot:run
```

Open:

- Portal: `http://localhost:8080/`
- Health: `http://localhost:8080/actuator/health`
- OIDC metadata: `http://localhost:8080/.well-known/openid-configuration`
- JWKS: `http://localhost:8080/oauth2/jwks`

## Google OAuth Setup

Create a Google OAuth web client and add this authorized redirect URI for local development:

```text
http://localhost:8080/login/oauth2/code/google
```

For deployment, add the deployed equivalent:

```text
https://auth.your-domain.com/login/oauth2/code/google
```

Set these environment variables in the deployed service:

```text
GOOGLE_CLIENT_ID
GOOGLE_CLIENT_SECRET
AUTH_GATEWAY_ISSUER
```

`AUTH_GATEWAY_ISSUER` must be the public base URL of this service, for example:

```text
https://auth.liangwendev.com
```

## VPS Deployment Shape

Prefer a dedicated auth subdomain:

```text
https://auth.liangwendev.com
```

Keep `https://liangwendev.com` for the CV/home page. Add a normal link or button from the CV page to `https://auth.liangwendev.com/` if you want an entry point.

Avoid mounting the gateway at `https://liangwendev.com/login` for production OIDC. Path-mounted auth servers make issuer URLs, discovery metadata, callback URLs, and downstream app configuration easier to misconfigure.

DNS:

```text
auth.liangwendev.com A <your-vps-public-ip>
```

Google OAuth authorized redirect URI:

```text
https://auth.liangwendev.com/login/oauth2/code/google
```

Runtime environment:

```text
PORT=8080
AUTH_GATEWAY_ISSUER=https://auth.liangwendev.com
GOOGLE_CLIENT_ID=<google-client-id>
GOOGLE_CLIENT_SECRET=<google-client-secret>
SESSION_COOKIE_SECURE=true
ALLOWED_EMAILS=your-email@example.com
ALLOWED_DOMAINS=
REQUIRE_VERIFIED_EMAIL=true
AUTH_GATEWAY_JWT_PRIVATE_KEY_PATH=/etc/auth-gateway/auth-gateway-private-key.pem
AUTH_GATEWAY_JWT_KEY_ID=auth-gateway-main
DATABASE_URL=jdbc:postgresql://127.0.0.1:5432/auth_gateway
DATABASE_USERNAME=auth_gateway
DATABASE_PASSWORD=<postgres-password>
JPA_DDL_AUTO=update
H2_CONSOLE_ENABLED=false
```

### Persistent JWT Signing Key

Production must use a persistent RSA private key. If the key changes on every restart, downstream platforms may reject tokens issued before the restart because the JWKS has changed.

Generate a PKCS#8 RSA key:

```bash
mkdir -p /etc/auth-gateway
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out /etc/auth-gateway/auth-gateway-private-key.pem
chmod 600 /etc/auth-gateway/auth-gateway-private-key.pem
```

For local Docker Compose, use:

```bash
./scripts/generate-signing-key.sh
```

Then set:

```text
AUTH_GATEWAY_JWT_PRIVATE_KEY_PATH=/etc/auth-gateway/auth-gateway-private-key.pem
AUTH_GATEWAY_JWT_KEY_ID=auth-gateway-main
```

`AUTH_GATEWAY_JWT_PRIVATE_KEY` is also supported for environments that inject multi-line secrets directly.

### Google Access Control

By default, local development allows any verified Google account. For production, set at least one allowlist:

```text
ALLOWED_EMAILS=you@gmail.com,admin@example.com
ALLOWED_DOMAINS=example.com
REQUIRE_VERIFIED_EMAIL=true
```

If both `ALLOWED_EMAILS` and `ALLOWED_DOMAINS` are empty, any verified Google account can sign in.

### PostgreSQL

Use PostgreSQL in production:

```text
DATABASE_URL=jdbc:postgresql://127.0.0.1:5432/auth_gateway
DATABASE_USERNAME=auth_gateway
DATABASE_PASSWORD=<postgres-password>
JPA_DDL_AUTO=update
```

H2 remains useful for local development, but do not use the file-based H2 database as the long-term production database.

Example Nginx reverse proxy:

```nginx
server {
    listen 80;
    server_name auth.liangwendev.com;
    return 301 https://$host$request_uri;
}

server {
    listen 443 ssl http2;
    server_name auth.liangwendev.com;

    ssl_certificate /etc/letsencrypt/live/auth.liangwendev.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/auth.liangwendev.com/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
        proxy_set_header X-Forwarded-Host $host;
        proxy_set_header X-Forwarded-Port 443;
    }
}
```

With this setup, do not expose port `8080` publicly. Let the Java app bind locally and expose only `80/443` through Nginx.

The same Nginx config is also available at `deploy/nginx/auth-gateway.conf`.

### Docker Compose Deployment

On the VPS:

```bash
git clone https://github.com/gpalw/auth-gateway.git
cd auth-gateway
cp .env.production.example .env.production
./scripts/generate-signing-key.sh
```

Edit `.env.production`, then start:

```bash
docker compose --env-file .env.production up -d --build
```

The compose file binds the Java service to `127.0.0.1:8080`, so Nginx should be the public HTTPS entry point.

### Systemd Deployment

If running without Docker:

```bash
sudo useradd --system --home /opt/auth-gateway --shell /usr/sbin/nologin auth-gateway
sudo mkdir -p /opt/auth-gateway /etc/auth-gateway
sudo cp target/auth-gateway-0.0.1-SNAPSHOT.jar /opt/auth-gateway/auth-gateway.jar
sudo cp deploy/systemd/auth-gateway.env.example /etc/auth-gateway/auth-gateway.env
sudo cp deploy/systemd/auth-gateway.service /etc/systemd/system/auth-gateway.service
sudo openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out /etc/auth-gateway/auth-gateway-private-key.pem
sudo chown -R auth-gateway:auth-gateway /opt/auth-gateway /etc/auth-gateway
sudo chmod 600 /etc/auth-gateway/auth-gateway-private-key.pem
```

Edit `/etc/auth-gateway/auth-gateway.env`, then:

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now auth-gateway
sudo systemctl status auth-gateway
```

## Built-In Local Clients

The default config includes two local OIDC clients:

| Client | Client ID | Redirect URI |
| --- | --- | --- |
| Job CRM | `job-crm-local` | `http://localhost:3000/login/oauth2/code/auth-gateway` |
| Interview Intelligence | `interview-local` | `http://localhost:3000/login/oauth2/code/auth-gateway` |

Client secrets are read from:

```text
JOB_CRM_CLIENT_SECRET
INTERVIEW_CLIENT_SECRET
```

Local fallback values exist only so the app can boot without real secrets. Deployment should set real secrets.

## Downstream Spring Boot App

A Spring Boot platform can use this gateway as an OAuth2 login provider:

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          auth-gateway:
            client-id: job-crm-local
            client-secret: ${JOB_CRM_CLIENT_SECRET}
            authorization-grant-type: authorization_code
            redirect-uri: "{baseUrl}/login/oauth2/code/{registrationId}"
            scope:
              - openid
              - profile
              - email
        provider:
          auth-gateway:
            issuer-uri: http://localhost:8080
```

After login, store the token `sub` claim as the app's user key. This gateway sets `sub` and `user_id` to the internal gateway user id.

## Downstream Next.js Or Node App

Use any OIDC-compatible library with:

```text
issuer: http://localhost:8080
client_id: interview-local
client_secret: ${INTERVIEW_CLIENT_SECRET}
authorization_endpoint: http://localhost:8080/oauth2/authorize
token_endpoint: http://localhost:8080/oauth2/token
jwks_uri: http://localhost:8080/oauth2/jwks
userinfo_endpoint: http://localhost:8080/userinfo
```

For production, replace `http://localhost:8080` with `AUTH_GATEWAY_ISSUER`.

## User Claims

Downstream platforms should read:

```text
sub       internal gateway user id
user_id   same as sub
email     user email
name      display name
picture   avatar URL when available
```

Avoid using Google's `sub` as the platform-wide user id. Google identity is an external account link owned by this gateway.

## Secrets

Do not commit real Google client credentials or platform client secrets. Use deployment environment variables or the platform's secret manager.
