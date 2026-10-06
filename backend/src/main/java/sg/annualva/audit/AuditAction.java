package sg.annualva.audit;

/**
 * What an audit entry records. A later ticket that adds an auditable action adds its constant here. The ECS
 * {@code event.action} is the kebab-case code, kept as the earlier application log events named them so that what
 * an operator already searches for still works.
 */
public enum AuditAction {
    SIGN_IN("sign-in", "authentication"),
    SIGN_OUT("sign-out", "authentication"),
    /** A sign-in the application refused or that failed at the identity provider. */
    SIGN_IN_FAILED("sign-in-refused", "authentication"),
    STEP_UP_STARTED("step-up-started", "authentication"),
    /** The fallback that ends the session and demands a full sign-in instead of a step-up. */
    STEP_UP_FULL_SIGN_IN("step-up-full-sign-in", "authentication"),
    STEP_UP_OK("step-up", "authentication"),
    STEP_UP_FAILED("step-up-refused", "authentication"),
    /** A request answered 401 or 403: not signed in, not allowed, no CSRF token, or a step-up needed. */
    REQUEST_REFUSED("request-refused", "web"),
    ROLE_GRANTED("role-granted", "iam"),
    ROLE_REVOKED("role-revoked", "iam"),
    /** A VA POC downloaded the System Register template; it names no System and holds no data. */
    REGISTER_TEMPLATE_DOWNLOADED("register-template-downloaded", "file"),
    /** A VA POC uploaded a Register file and a preview of its changes was made; nothing in the Register changed. */
    REGISTER_PREVIEWED("register-previewed", "file"),
    /** A VA POC read a Register change preview back. */
    REGISTER_PREVIEW_VIEWED("register-preview-viewed", "file"),
    /** A Register file was refused (not a workbook, too large, wrong headers); the entry holds a fixed code only. */
    REGISTER_UPLOAD_REJECTED("register-upload-rejected", "file");

    private final String ecs;
    private final String category;

    AuditAction(String ecs, String category) {
        this.ecs = ecs;
        this.category = category;
    }

    /** The ECS {@code event.action} value. */
    String ecs() {
        return ecs;
    }

    /** The ECS {@code event.category} value. */
    String category() {
        return category;
    }
}
