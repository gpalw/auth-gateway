# Auth Gateway CI/CD

This repository deploys `auth-gateway` to the production VPS with GitHub Actions.

## Pipeline

Pushes to `main` and manual `workflow_dispatch` runs execute:

1. Check out the repository.
2. Set up Java 21.
3. Run `./mvnw -B test`.
4. Build the Spring Boot jar.
5. Upload the jar as a workflow artifact.
6. SSH to the VPS.
7. Copy the jar and `scripts/deploy-systemd.sh` to `/tmp`.
8. Run the deployment script with `sudo`.
9. Restart `auth-gateway.service`.
10. Verify `/actuator/health`.
11. Restore the previous jar if the new release does not become healthy.

## Required GitHub Secrets

Configure these repository secrets:

```text
AUTH_GATEWAY_VPS_HOST=159.13.55.133
AUTH_GATEWAY_VPS_PORT=22
AUTH_GATEWAY_VPS_USER=ubuntu
AUTH_GATEWAY_VPS_SSH_KEY=<private key for the deploy-only SSH key>
```

The matching public key must be present in `/home/ubuntu/.ssh/authorized_keys`
on the VPS. The `ubuntu` user must be able to run `sudo -n true` because the
deployment script writes to `/opt/auth-gateway` and restarts systemd.

## VPS Layout

The production server keeps runtime secrets outside the repository:

```text
/etc/auth-gateway/auth-gateway.env
/etc/auth-gateway/auth-gateway-private-key.pem
/opt/auth-gateway/data/
```

The deployment script only replaces:

```text
/opt/auth-gateway/auth-gateway.jar
```

It also writes operational history:

```text
/opt/auth-gateway/releases/<git-sha>/
/opt/auth-gateway/backups/<timestamp>/
/opt/auth-gateway/current-release -> /opt/auth-gateway/releases/<git-sha>/
```

## Health Check

By default the script reads `SERVER_ADDRESS` and `PORT` from
`/etc/auth-gateway/auth-gateway.env` and checks:

```text
http://<SERVER_ADDRESS>:<PORT>/actuator/health
```

You can override this for a one-off run:

```bash
sudo HEALTH_URL=http://127.0.0.1:19090/actuator/health \
  bash scripts/deploy-systemd.sh /tmp/auth-gateway.jar manual
```

## Manual Rollback

If an operator needs to roll back manually:

```bash
sudo install -m 0644 /opt/auth-gateway/backups/<timestamp>/auth-gateway.jar /opt/auth-gateway/auth-gateway.jar
sudo systemctl restart auth-gateway.service
curl -fsS http://127.0.0.1:19090/actuator/health
```
