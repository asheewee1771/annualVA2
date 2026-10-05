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
