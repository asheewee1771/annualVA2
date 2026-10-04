# Authorities resolved per request from the role store; sessions in PostgreSQL

Status: accepted

Roles are held in the app, never taken from token claims, and IM8 ac-3 wants access to end immediately when a person is no longer named in the System Register or is disabled at the identity provider. Caching authorities in the session at sign-in would keep access alive until the session ends. A filter resolves the caller's active grants from `role_grant` on every request. Sessions themselves are stored with Spring Session JDBC so a restart or a second node does not sign everyone out.

Consequences: one indexed query per request (negligible at this scale); revoking, lapsing or suspending a grant takes effect on the next request; `last_used_at` is touched at most once per day per grant to keep the write cheap.
