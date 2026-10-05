# 56: Onboard the application to MCC SSO through MTM

**Spec:** [Annual VA spec](../spec.md), stories 72, 77 (SIT).
**Release:** 1
**What to build:** The real identity provider knows the application: client ids, keys, redirect URIs, the staff `kc_idp_hint`, the `afcas.uuid` mapper, and confirmation of the `auth_time`, MFA claim and `prompt=login` behaviour that sign-in and step-up rely on.
**Blocked by:** 03 (Sign in through MCC SSO and see my session), 09 (Step-up: a fresh sign-in before sensitive actions)
**Status:** ready-for-human
**Effort (orchestrator estimate):** about 15.0 developer days across stories 72 (12.0), 77 (3.0); `*` marks the critical path. See `reports/dev-report.md`.

- [ ] Login and service clients exist in SIT with the EC key registered and the redirect URI `/login/oauth2/code/mcc-sso-login`; the values go into `application-sit.yml` with `${VAR:dev-default}` bindings and `.env.sit.example`.
- [ ] It is confirmed in SIT whether MCC SSO honours `prompt=login` and returns `auth_time`, `amr` or `acr`; the answers are recorded, the MFA-claim check is configured accordingly and, if step-up is not honoured, the fallback (forced full sign-in) is enabled and ADR 0004 updated.
- [ ] The SIT trust bundle is on the classpath and sign-in works end to end in SIT.

## Comments

### Agent preparation (2026-10-05)

Status stays `ready-for-human`: no box above is ticked, because each one needs the MTM onboarding or a SIT environment that only a person can reach. Done by the agent so that the human steps are configuration and observation only:

- `backend/src/main/resources/application-sit.yml`: the `sit` profile with `${VAR:dev-default}` bindings for the database URL and user, the MCC SSO endpoints, login client id, `kc_idp_hint`, signing kid, the trust bundle path, `require-mfa-claim`, `require-verified-email`, `mfa-amr-values`, `mfa-acr-values` and `step-up.mode`. The defaults are the mock stack's non-secret values, not SIT values. The database password, the signing key pair and `ANNUALVA_VA_POCS` have no default (a SIT start without them fails). `spring.ssl.bundle.pem.mcc-trust` points at `classpath:sit/mcc-sso-ca.crt` (overridable with `ANNUALVA_MCC_TRUST_CERTIFICATE`) and `annualva.mcc.ssl-bundle` is `mcc-trust`.
- `backend/.env.sit.example` lists every key; an optional `backend/.env.sit` is loaded through Spring Boot's own `spring.config.import` (checked by hand: a value in `.env.sit` overrides the default). No `EnvironmentPostProcessor` was written; ADR 0005 is updated to say so.
- `SitProfileTest` (Seam 1) starts the application with the `sit` profile and checks the MFA and verified-email checks are on, step-up is `prompt-login`, the trust bundle is named and the application starts while the SIT certificate file is still absent. Back end gate: 117 tests green, coverage met.
- README section "SIT onboarding checks (ticket 56)": how to read each answer from the ECS log (`sign-in`, `sign-in-refused` reasons `mfa-not-evidenced`, `email-not-verified`, `missing-auth-time`, `missing-identity`; `step-up` and `step-up-refused` with `stale-auth-time`).

**Left for a human (nothing here was invented)**
1. Through MTM: register the login client and the service client in SIT, register the EC P-521 public key with its kid, the redirect URI `/login/oauth2/code/mcc-sso-login`, the staff `kc_idp_hint` value and the `afcas.uuid` mapper. Generate the SIT key pair (not the dev-mcc one) and put the values in the SIT environment (or `.env.sit`).
2. Put the SIT CA certificate at `backend/src/main/resources/sit/mcc-sso-ca.crt` (public certificate, so it may be committed) and commit it with the real SIT endpoint values supplied through the environment.
3. Sign in on SIT and record here: does MCC SSO supply `amr` or `acr` (which values), a boolean `email_verified`, `auth_time`; does it honour `prompt=login` and `max_age=0` through `/auth/step-up?intent=sit-check&returnTo=/`. Then set the flags accordingly. If `prompt=login` is ignored, set `ANNUALVA_STEPUP_MODE=full-sign-in`, update ADR 0004, and note that `full-sign-in` ends only the application session, so MCC SSO's own session may sign the person straight back in with the old `auth_time` (an end-session call to MCC SSO would then be a new ticket).
4. Ticket 01 (mock-m-sso) is still open, so `dev-mcc` sign-in has not been run against Keycloak; the claim shapes are asserted only by the fake issuer.
