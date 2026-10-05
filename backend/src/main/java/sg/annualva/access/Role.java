package sg.annualva.access;

/**
 * The four roles, in the order a person's first role is chosen: the highest privilege first. The
 * session lists a person's roles in this order and the front end lands them on the first one.
 */
public enum Role {
    VA_POC,
    CYBER_MANAGER,
    SMO,
    AUDITOR;

    /** The Spring Security authority a held role becomes ({@code hasRole('VA_POC')}). */
    public String authority() {
        return "ROLE_" + name();
    }
}
