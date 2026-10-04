# Step-up is a fresh OIDC sign-in with an auth_time check

Status: accepted

IM8 ac-2 and story 77 require a fresh MFA sign-in before sensitive actions. The org SSO standards specify no mechanism; the spec assumes MCC SSO supports step-up on demand, otherwise the app forces a full re-login. We reuse the identity provider's MFA: a sensitive command requires the session's `auth_time` to be within 5 minutes; otherwise the API answers 403 `step-up-required`, the SPA sends the user through `/auth/step-up`, which starts a new authorization request with `prompt=login` and `max_age=0`, and the callback verifies `auth_time` (failing closed when it is absent) and refreshes the session marker without extending the session.

Consequences: no second credential store and no dependency on MCNS on the critical path; MCC SSO must honour `prompt=login` (verified at SIT), otherwise the same component falls back to invalidating the session and forcing a full sign-in; app-level OTP via MCNS stays a documented fallback behind the same port; sensitive actions are never replayed automatically after step-up.
