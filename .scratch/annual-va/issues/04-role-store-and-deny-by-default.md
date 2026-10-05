# 04: Roles held in the app and every endpoint denied by default

**Spec:** [Annual VA spec](../spec.md), stories 75, 76, 78.
**Release:** 1
**What to build:** Roles come only from the app's role store, never from token claims. The two VA POCs are configured at deployment; a person sees only what their active roles allow and a resource they may not see answers exactly like one that does not exist.
**Blocked by:** 03 (Sign in through MCC SSO and see my session)
**Status:** ready-for-agent
**Effort (orchestrator estimate):** about 6.3 developer days across stories 75 (1.3), 76 (2.0), 78 (3.0); `*` marks the critical path. See `reports/dev-report.md`.

- [ ] Role grants (role, person or email, System, source, granted, expires, lapsed, suspended, revoked, last used) drive authorisation; authorities are resolved from the store on every request so a revoked grant takes effect on the next call.
- [ ] Exactly two VA POC emails come from configuration; the app refuses to start with fewer or more, and syncs them to grants with source CONFIG at startup. A grant made by email before the person has signed in attaches to them on first sign-in.
- [ ] Every endpoint outside the anonymous list carries a method-level authorisation rule (architecture test), and every service path that addresses a System or a VA Submission checks ownership; a caller without scope on an existing resource receives the same 404 as for a missing one.
- [ ] The only ways a role changes are a Register apply, configuration and (later) the role-holder review; there is no endpoint through which a person can grant a role, so story 76 holds by construction and is re-checked when the review arrives.
- [ ] `GET /api/session` lists the caller's active roles; the front end routes a person to their first role's landing area and shows one nav entry per role held.
- [ ] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments
