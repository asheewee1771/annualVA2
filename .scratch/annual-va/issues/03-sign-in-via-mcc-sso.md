# 03: Sign in through MCC SSO and see my session

**Spec:** [Annual VA spec](../spec.md), stories 72.
**Release:** 1
**What to build:** Any user signs in through the organisation's single sign-on (MockPass via Keycloak locally), gets a server-side session, and `GET /api/session` shows who they are. Signing out ends the session. Nobody can sign in any other way.
**Blocked by:** 02 (Walking skeleton: repository, security baseline and Seam 1 harness)
**Status:** ready-for-agent
**Effort (orchestrator estimate):** about 12.0 developer days across stories 72 (12.0); `*` marks the critical path. See `reports/dev-report.md`.

- [ ] `GET /oauth2/authorization/mcc-sso-login` starts an authorization-code flow with PKCE S256 and `private_key_jwt` client authentication (ES512 with the configured EC key and kid), scope `openid afcas`, `kc_idp_hint` from configuration and explicit provider endpoints (no `issuer-uri`); `/.well-known/jwks.json` publishes the public key anonymously.
- [ ] On the first sign-in the person is linked by verified email to a pre-registered email where one exists, otherwise a new user record is created; afterwards the person is recognised by `afcas.uuid` (nested or flat claim) even if the email changes. A token with no `afcas.uuid` or no `auth_time` is refused and no session is created.
- [ ] MFA at every sign-in: a token whose `amr` or `acr` claim shows no multi-factor evidence is refused when the identity provider supplies those claims; whether MCC SSO supplies them is confirmed by the SSO onboarding ticket and the check is configurable per environment, never silently skipped.
- [ ] The session id rotates at sign-in, tokens are discarded, sessions survive an application restart, idle out after 30 minutes and end after 8 hours regardless, and a second sign-in by the same person ends the first session.
- [ ] `GET /api/session` returns `authenticated`, `uuid`, `email`, `name`, `roles` (empty for now), `classification`, `idleTimeoutSeconds` and `absoluteExpiresAt`; `POST /api/logout` needs the CSRF token, ends the session and sends `Clear-Site-Data`.
- [ ] Sign-in, sign-out and refused sign-ins are logged as ECS events; `POST /login` and any form login return 404. The front end shows the signed-in header and a sign-out button, and a 401 from any call returns it to the signed-out screen.
- [ ] The Seam 1 harness gains a fake OIDC issuer that mints real signed tokens for any test identity with a chosen `auth_time` and MFA claims, and the `dev-mcc` profile maps MockPass persona uuids to dev emails.
- [ ] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments
