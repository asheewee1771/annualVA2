/**
 * Access: sign-in through the organisation's identity provider, linking a sign-in to a user
 * (by {@code sso_id}, else by pre-registered email, else a new user), the session lifecycle
 * (rotation at sign-in, idle and absolute limits, one session per person, sign-out) and the
 * security wiring the composition root applies. Roles and step-up arrive with later slices.
 * Depends on boundary and config only.
 */
package sg.annualva.access;
