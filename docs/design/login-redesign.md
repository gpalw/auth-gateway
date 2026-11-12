# Identity experience refresh

## Design

The sign-in experience now has its own public page at `/login`, rather than sending an anonymous visitor straight to Google. A dark mineral-green identity panel, warm paper surface, original architectural gateway illustration, and restrained terracotta accents form a shared visual system. The signed-in workspace and signed-out screen use the same system. Admin screens retain their existing stylesheet.

The illustration and layout are original. There is no copied third-party theme, framework, webfont, remote stylesheet, tracking pixel, or JavaScript dependency. The four-color Google mark identifies the existing Google sign-in provider; it does not imply additional provider support.

## Behavior

- Anonymous HTML requests enter `/login`. API requests retain their 401 response.
- The Google action uses the existing `/oauth2/authorization/google` endpoint and existing OAuth callback and account policy.
- Downstream OIDC authorization requests remain in Spring Security's request cache while the login page is displayed.
- An authenticated visitor to `/login` returns to the workspace.
- Failed login has a generic, recoverable message. Query strings and provider exception details are not rendered.
- Missing Google registration or the built-in development placeholder credentials show an honest setup state, without an active sign-in action. This is a configuration check, not a live provider health check.
- Sign-out is still a CSRF-protected POST. It ends the gateway session and lands on `/signed-out`, with no automatic sign-in restart. Copy explicitly distinguishes gateway, Google, and downstream app sessions.
- The workspace uses the real enabled-platform list and signed-in claims. No provider buttons, sample applications, account identity, legal links, or availability badges are fabricated.

## Accessibility and responsive layout

Semantic landmarks and headings, skip links, visible keyboard focus, accessible Google button text, generic alert messaging, no essential animation, reduced-motion support, no hover-only information, and 42–56 px actions. The two-panel layout becomes a focused single-column sign-in screen below 761 px. Workspace cards adapt to one, two, or three columns. Long names wrap instead of overflowing.

## Run and preview

With Java 21 and the normal repository environment:

```sh
./mvnw clean verify
./mvnw spring-boot:run
```

Open `http://localhost:8080/login`. Real sign-in requires the existing Google client ID and secret environment variables, with the registered callback URL. Do not use preview credentials for production.

For reproducible visual fixtures, run:

```sh
./mvnw -Dauth.ui.previewDir=target/ui-preview test
cp src/main/resources/static/auth.css target/ui-preview/auth.css
```

The test-generated HTML comes from the real Thymeleaf templates, using synthetic identity and client data. It includes login, login error, unconfigured login, signed out, workspace, empty workspace, and long-content variants. These files are **static visual fixtures**, not a working authentication server. Keep them under ignored `target/`, not the public application resources.

Where JVM self-attachment is restricted, launch tests with the matching Mockito jar as an explicit `-javaagent` in `-DargLine`. This is a test-runtime workaround and does not change production dependencies.

## Verification scope

Integration tests cover public CSS/login, configured and unconfigured providers, actual Google authorization request generation, saved downstream authorization, authenticated login visits, safe errors, API 401, CSRF logout and session invalidation, account escaping, enabled apps, empty state, and long content. Visual inspection should include desktop, narrow mobile, error, and signed-out views.

No live Google account login, production database change, deployment, or remote push is part of this refresh. Complete a real Google round trip in the intended staging environment before deployment.

### Execution result for this patch

`clean verify` passed **71 tests**, with zero failures, errors, or skips. An independent static review found no concrete regression. Visual browser checks remain **not completed**: sandboxed Chromium could not create its required local sockets, and the available preview browser could not reach the temporary local HTTP server. The offline design preview is supplied for review; it is not evidence of a screenshot-verified release. No attempt was made to bypass the environment restrictions or publish the preview.
