package sg.annualva.audit;

/** How an audited action ended. */
public enum AuditOutcome {
    /** It happened. */
    SUCCESS("success"),
    /** The rules said no: a refused request, a refused sign-in. */
    REFUSED("failure"),
    /** It was attempted and something went wrong. */
    FAILED("failure");

    private final String ecs;

    AuditOutcome(String ecs) {
        this.ecs = ecs;
    }

    /** The ECS {@code event.outcome} value. */
    String ecs() {
        return ecs;
    }
}
