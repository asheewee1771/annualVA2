package sg.annualva.access;

/** Where a role grant came from: the only three ways a role is ever given. */
public enum GrantSource {
    /** The two VA POCs, set at deployment. */
    CONFIG,
    /** A System Register apply (SMO/Tech Lead and Cyber Manager, per System). */
    REGISTER,
    /** A VA POC granting an Auditor. */
    GRANT
}
