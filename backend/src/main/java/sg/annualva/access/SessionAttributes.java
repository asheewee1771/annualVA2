package sg.annualva.access;

/** Names of the session attributes the access module keeps beside the security context. */
final class SessionAttributes {

    /** {@code Instant}: when the session was established; drives the absolute limit. */
    static final String SIGNED_IN_AT = "annualva.signedInAt";

    /** {@code Instant}: the last request seen, by the application clock; drives the idle limit. */
    static final String LAST_SEEN_AT = "annualva.lastSeenAt";

    /** {@code String}: the user id, for the session-ended event (never an email). */
    static final String USER_ID = "annualva.userId";

    private SessionAttributes() {}
}
