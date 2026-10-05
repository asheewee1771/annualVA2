/**
 * Access: sign-in through the organisation's identity provider, linking a sign-in to a user
 * (by {@code sso_id}, else by pre-registered email, else a new user), the session lifecycle
 * (rotation at sign-in, idle and absolute limits, one session per person, sign-out), the role
 * store and the security wiring the composition root applies.
 *
 * <p>Roles are held here and nowhere else: the {@code role_grant} table, read on every request into
 * a {@link sg.annualva.access.RoleAuthentication} (ADR 0011), never taken from token claims. The two
 * VA POCs come from configuration ({@link sg.annualva.access.VaPocProperties}); SMO/Tech Lead and
 * Cyber Manager grants will come from the System Register apply. {@link sg.annualva.access.AccessGuard}
 * is the per-System ownership check every service path that addresses a System must call (ADR 0013).
 * Step-up arrives with a later slice. Depends on boundary, config and web only.
 */
package sg.annualva.access;
