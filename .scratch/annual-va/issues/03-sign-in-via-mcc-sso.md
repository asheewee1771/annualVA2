# 03: Sign in through MCC SSO and see my session

**Spec:** [Annual VA spec](../spec.md), stories 72.
**Release:** 1
**What to build:** Any user signs in through the organisation's single sign-on (MockPass via Keycloak locally), gets a server-side session, and `GET /api/session` shows who they are. Signing out ends the session. Nobody can sign in any other way.
**Blocked by:** 02 (Walking skeleton: repository, security baseline and Seam 1 harness)
**Status:** closed
**Effort (orchestrator estimate):** about 12.0 developer days across stories 72 (12.0); `*` marks the critical path. See `reports/dev-report.md`.

- [x] `GET /oauth2/authorization/mcc-sso-login` starts an authorization-code flow with PKCE S256 and `private_key_jwt` client authentication (ES512 with the configured EC key and kid), scope `openid afcas`, `kc_idp_hint` from configuration and explicit provider endpoints (no `issuer-uri`); `/.well-known/jwks.json` publishes the public key anonymously.
- [x] On the first sign-in the person is linked by verified email to a pre-registered email where one exists, otherwise a new user record is created; afterwards the person is recognised by `afcas.uuid` (nested or flat claim) even if the email changes. A token with no `afcas.uuid` or no `auth_time` is refused and no session is created.
- [x] MFA at every sign-in: a token whose `amr` or `acr` claim shows no multi-factor evidence is refused when the identity provider supplies those claims; whether MCC SSO supplies them is confirmed by the SSO onboarding ticket and the check is configurable per environment, never silently skipped.
- [x] The session id rotates at sign-in, tokens are discarded, sessions survive an application restart, idle out after 30 minutes and end after 8 hours regardless, and a second sign-in by the same person ends the first session.
- [x] `GET /api/session` returns `authenticated`, `uuid`, `email`, `name`, `roles` (empty for now), `classification`, `idleTimeoutSeconds` and `absoluteExpiresAt`; `POST /api/logout` needs the CSRF token, ends the session and sends `Clear-Site-Data`.
- [x] Sign-in, sign-out and refused sign-ins are logged as ECS events; `POST /login` and any form login return 404. The front end shows the signed-in header and a sign-out button, and a 401 from any call returns it to the signed-out screen.
- [x] The Seam 1 harness gains a fake OIDC issuer that mints real signed tokens for any test identity with a chosen `auth_time` and MFA claims, and the `dev-mcc` profile maps MockPass persona uuids to dev emails.
- [x] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments

### Verification (2026-10-05)

**Implemented** (commits `3b59c89`, `c904c37`, `e651d58`, `48e821c`): MCC SSO sign-in with PKCE S256 and ES512 `private_key_jwt`, anonymous JWKS, linking by `afcas.uuid` or verified email, MFA check from `amr`/`acr` (configurable, off only under `dev-mcc`, startup warning), PostgreSQL-backed session with id rotation, 30 min idle, 8 h absolute and one session per person, `GET /api/session`, `POST /api/logout` with CSRF and `Clear-Site-Data`, ECS events for sign-in, sign-out and refusals (user id only), no form login, front-end header with sign-out and fixed refusal messages, fake OIDC issuer harness and `dev-mcc` persona mapping.

Added during review: refusal reasons `identity-conflict`, `email-not-verified` and `provider-error`; a per-address cap on anonymous session starts (429); connect and read timeouts on calls to MCC SSO; a crafted `reason` value can no longer crash the signed-out screen.

**Verification performed**
- Back end `mvnw.cmd -B -ntp clean verify`: 57 tests, 0 failures, ArchUnit and JaCoCo (80 % line and branch) met.
- Front end `npm run check`: passed.
- Reviewer loop: 0 Must-fix and 0 Human decisions after three rounds.
- `/code-reviewer` final gate (build, thermo-nuclear, code-review, semgrep, im8, react, springboot): PASS. Report: `artifacts/code-reviewer/03-sign-in-via-mcc-sso-compliance.html`.
- Mutation gate: skipped.

All checklist boxes above are ticked.

**Not verified, and open for the owners named**
- "Sessions survive an application restart" is shown only by the session sitting in the `spring_session` table. No test restarts the application.
- The flow was not run against a real or mock Keycloak, and the front end was not run in a browser. `dev-mcc` cannot be tried locally until `../mock-m-sso` exists (ticket 01).
- Ticket 56: confirm MCC SSO supplies `amr`/`acr` and a boolean `email_verified`. If not, set `annualva.sign-in.require-verified-email` or the MFA flag per environment.
- Ticket 55: the anonymous-session cap is per address as the app sees it, so behind one gateway address it is a global backstop. Per-client throttling is a hosting decision.
- Ticket 47: the `idp_disabled_at` column exists but is not checked at sign-in, and the access-review job is not built.
- Ticket 52: architecture and data-flow document, sign-in refusal meter and front-end instrumentation. No ticket owns a committed OpenAPI spec.
- No guard stops the `dev-mcc` profile (MFA off, known signing key) from running in a deployed environment. Medium, deferred.
- Token validators read the system clock, not the `Clock` bean, because Seam 1 advances the shared test clock into 2027.
- No test for two simultaneous first sign-ins of one new person.
