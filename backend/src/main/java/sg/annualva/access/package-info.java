/**
 * Access: sign-in through the organisation's identity provider, linking a sign-in to a user
 * (by {@code sso_id}, else by pre-registered email, else a new user), the session lifecycle
 * (rotation at sign-in, idle and absolute limits, one session per person, sign-out), the role
 * store and the security wiring the composition root applies.
 *
 * <p>Roles are held here and nowhere else: the {@code role_grant} table, read on every request into
 * a {@link sg.annualva.access.RoleAuthentication} (ADR 0011), never taken from token claims. The two
 * VA POCs come from configuration ({@link sg.annualva.access.VaPocProperties}); SMO/Tech Lead and
 * Cyber Manager grants come from the System Register apply ({@link sg.annualva.access.RegisterRoles}, ADR 0018). A grant
 * unused for 90 days lapses and comes back by itself when the Register, a Quarter's start or work handed over names the
 * person, a VA POC's only through configuration ({@link sg.annualva.access.RoleLapse}, ADR 0055). {@link sg.annualva.access.AccessGuard}
 * is the per-System ownership check every service path that addresses a System must call (ADR 0013).
 * Step-up ({@link sg.annualva.access.StepUp}) makes a sensitive command require a sign-in within the last
 * five minutes and runs the fresh sign-in round trip (ADR 0004). Everything it does that must be accountable (sign-in,
 * sign-out, step-up, refused requests, VA POC role changes) goes to the audit log through {@link sg.annualva.access.AccessAudit}.
 * Depends on audit, boundary, config and web only.
 */
package sg.annualva.access;
