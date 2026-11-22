# 04: Roles held in the app and every endpoint denied by default

**Spec:** [Annual VA spec](../spec.md), stories 75, 76, 78.
**Release:** 1
**What to build:** Roles come only from the app's role store, never from token claims. The two VA POCs are configured at deployment; a person sees only what their active roles allow and a resource they may not see answers exactly like one that does not exist.
**Blocked by:** 03 (Sign in through MCC SSO and see my session)
**Status:** closed
**Effort (orchestrator estimate):** about 6.3 developer days across stories 75 (1.3), 76 (2.0), 78 (3.0); `*` marks the critical path. See `reports/dev-report.md`.

- [x] Role grants (role, person or email, System, source, granted, expires, lapsed, suspended, revoked, last used) drive authorisation; authorities are resolved from the store on every request so a revoked grant takes effect on the next call.
- [x] Exactly two VA POC emails come from configuration; the app refuses to start with fewer or more, and syncs them to grants with source CONFIG at startup. A grant made by email before the person has signed in attaches to them on first sign-in.
- [x] Every endpoint outside the anonymous list carries a method-level authorisation rule (architecture test), and every service path that addresses a System or a VA Submission checks ownership; a caller without scope on an existing resource receives the same 404 as for a missing one.
- [x] The only ways a role changes are a Register apply, configuration and (later) the role-holder review; there is no endpoint through which a person can grant a role, so story 76 holds by construction and is re-checked when the review arrives.
- [x] `GET /api/session` lists the caller's active roles; the front end routes a person to their first role's landing area and shows one nav entry per role held.
- [x] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments

### Verification (2026-10-05)

**Implemented** (commits `29e4397`, `33e22fb`, `8d40e5f`, `d0b888c`): a `role_grant` store (migration V4) with role, person or email, System, source, granted, expires, lapsed, suspended, revoked and last used; a request filter on `/api/**` that resolves authorities from the store on every call and never saves them to the session; two VA POCs from `annualva.va-pocs` (startup refuses anything but exactly two distinct valid emails, the message names the setting and not the values) synced as CONFIG grants; email grants attach at first sign-in for a verified email only; a method rule required on every controller method (architecture test) with `permitAll()` only on the anonymous list; one access guard where a System the caller may not see answers like a missing one (ADR 0013); no endpoint or token claim can grant a role; `GET /api/session` lists active roles; the front end routes to the first role's area with one nav entry per role. Also fixed: a method-security denial inside a controller returned 500 and now returns 403.

**Verification performed**
- Back end `mvnw.cmd -B -ntp clean verify`: 98 tests, 0 failures, ArchUnit met, JaCoCo 95.7 % line and 87.0 % branch.
- Front end `npm run check`: passed.
- Reviewer: 0 Must-fix and 0 Human decisions in the first round.
- `/code-reviewer` final gate (build, thermo-nuclear, code-review, semgrep, im8, react, springboot): PASS on the first run. Report: `artifacts/code-reviewer/04-role-store-and-deny-by-default-compliance.html`.
- Mutation gate: skipped.

All checklist boxes above are ticked.

**Not verified, and open for the owners named**
- The front end was not run in a browser; that needs the SSO stack (ticket 53).
- No System or VA Submission service exists yet, so the ownership guard is proven through test probe endpoints. Later tickets must call it from their service paths.
- `ANNUALVA_VA_POCS` is now required in the production-shaped config, so deployments must set it.
- `role_grant.system_id` has no foreign key yet; the Register apply ticket adds it with the System table.
- Deep links such as `/va-poc` work only through the Vite dev server, because the back end does not serve the SPA. Ticket 55 (hosting) decides this.
- Tickets 46 and 47: `RoleGrantStore.grant()` ignores a conflict, so a lapsed or suspended CONFIG VA POC grant that is not revoked stays that way across restarts. Check how a lapsed VA POC is reinstated and whether the lapse job exempts CONFIG grants.
- Reviewer should-fix items, not blocking: the method-rule architecture test checks only public methods in `@RestController` classes and ignores a class-level `@RequestMapping` prefix; confirm by hand that a bad `ANNUALVA_VA_POCS` does not echo the emails in Spring's startup failure output; client-side route changes do not move focus or set the document title; `onlyConfigurationCanMakeAVaPoc` asserts only that the status is not 200.
