# 52: Key-signal monitoring and system documentation

**Spec:** [Annual VA spec](../spec.md), stories lm-16, pm-6.
**Release:** 1
**What to build:** Operators can see the signals that matter (overdue submissions, scan failures and timeouts, mail failures, step-up refusals, audit-write failures) and the system is documented for hand-over.
**Blocked by:** 51 (Production adapters: S3 with SSE-KMS, SFS scanner, MCNS mail, MCC service client)
**Status:** ready-for-agent

- [ ] Meters for the listed signals are registered and exposed on an authenticated `/actuator/prometheus`; `/actuator/health` stays anonymous and liveness-only; a Seam 1 test reads each meter after the triggering action.
- [ ] The README gains a runbook section with the alert threshold and the operator response for each signal, and a profiles and secrets section for SIT, UAT and production.
- [ ] `/tech-arch-doc` produces the technical architecture document under `docs/`; a doc review confirms it names every module, boundary adapter, profile and ADR.

## Comments
