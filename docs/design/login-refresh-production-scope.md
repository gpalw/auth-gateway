# Login refresh production scope — 2026-11-12

This release combines the refreshed Google sign-in, signed-out page and workspace with the already-deployed private navigation MVP. Navigation keeps the owner-only verified Google account policy, safe launch URLs, enrolled probe, search, categories and exact application return paths. It uses local assets and an initial avatar so the private page makes no third-party requests.

The admin filter fix checks the original transport request before framework forwarding can rewrite its address or path. Forwarded loopback addresses and prefixes cannot make a public request appear to be an SSH tunnel. Existing Nginx admin blocking, runtime credentials, allowlists and issuer remain unchanged.

## Deferred credential rule

The complete integrated source is preserved on `integrate/login-refresh-20261112` at `52f26481a6f2ffd2231aebdf7e5d730b53ccab5e`. It also rejects placeholder client secrets in production. Read-only inspection confirmed that an enabled Online Billing registration still uses a legacy example value. Deploying that rule now would prevent the gateway from starting.

The production release therefore excludes that new rule and its associated tests while preserving existing credential behavior. No credential was generated, rotated or disabled. The rule must be deployed only after a coordinated update to the stored registration and downstream application's credential. Updating the seed environment variable alone does not replace the stored registration.

## Verification

The full integration passed `clean verify` with 97 tests passing and two environment-dependent skips. The final production release passed `clean verify`: 85 tests passed, one Bash availability check skipped, zero failures/errors. This includes the existing real Spring/Nginx/Chromium test against a loopback mock Google issuer. The browser test now explicitly clicks the Google action on the new login page and mirrors production's existing `/favicon.ico` 204 rule, which prevents background requests from replacing saved login return paths. Screenshots cover desktop, mobile, narrow mobile, login error, private navigation and signed out.

The original checkout and all uncommitted private-navigation files remain in place. A complete recoverable copy was made at `D:\Codex\2026-11-12\task\backups\auth-gateway-original-20261112` before integration. Source changes are made in the separate copy at `D:\Codex\2026-11-12\task\auth-gateway-integrated`.

Deployment replaces only the gateway JAR and release symlink after verifying a new private backup. Configuration, Nginx and public-homepage hashes are checked before and after; unhealthy startup or failed acceptance restores the previous JAR. Deployment receipts record the exact source revision, artifact hash and backup directory.
