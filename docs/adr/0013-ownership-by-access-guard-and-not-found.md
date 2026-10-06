# Ownership is checked by the access guard, and "not yours" answers "not found"

Status: accepted

Story 78 and IM8 ac-1 and as-7 want a person to see only the Systems their roles allow, and to learn nothing about the rest. A 403 for an existing resource and a 404 for a missing one would let anyone probe which Systems and VA Submissions exist. So every service path that addresses a System, or anything that belongs to one (a VA Submission, a Scan Report file, an export), first asks `AccessGuard.requireSystem(systemId, need)`. A caller without scope over the System gets `ResourceNotFoundException`, which becomes the same RFC 9457 `not-found` problem as for a resource that does not exist; the guard runs before the existence check, so both paths give one answer. A list asks `AccessGuard.scope(need)` and filters by it.

The guard decides from the grants the request resolved from the role store (ADR 0011), by need: `VIEW` (the System's SMO/Tech Lead or Cyber Manager, any VA POC), `SUBMIT` (its SMO/Tech Lead), `REVIEW` (its Cyber Manager), `ENDORSE` (a VA POC). A call with no resolved roles has no scope. An Auditor has no System scope until the Auditor slice adds scope rows. Role-level rules (who may call an endpoint at all) stay `@PreAuthorize` on the controller method and answer 403.

Consequences: each ticket that adds a path addressing a System calls the guard and proves, in its Seam 1 test, that a System the caller may not see answers like a missing one. The `system_id` on `role_grant` references `va_system` (migration V7, ADR 0018).
