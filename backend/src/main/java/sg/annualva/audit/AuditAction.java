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
    REGISTER_UPLOAD_REJECTED("register-upload-rejected", "file"),
    /** A VA POC applied a Register change preview; the entry holds counts and the preview id only. */
    REGISTER_APPLIED("register-applied", "configuration"),
    /** A System was retired by a Register apply; its history stays. One entry per System, about that System. */
    REGISTER_SYSTEM_RETIRED("register-system-retired", "configuration"),
    /**
     * An SMO/Tech Lead started the VA Submission for a System (SUCCESS), or was refused because it was already started
     * or its Quarter is Handled outside the app (REFUSED, with a fixed reason). It is about the System and, on success,
     * the VA Submission; it holds no text from the request.
     */
    SUBMISSION_STARTED("submission-started", "database"),
    /**
     * A Cyber Manager or VA POC read one VA Submission (story 84). The System's own SMO/Tech Lead reading it is not
     * recorded. It is about the System and the VA Submission and holds no detail.
     */
    SUBMISSION_VIEWED("submission-viewed", "database"),
    /**
     * An SMO/Tech Lead uploaded a Scan Report file and it was quarantined (SUCCESS), or the upload was refused because the
     * VA Submission takes no files now or the Scan Report is full (REFUSED, with a fixed reason). About the System and the
     * VA Submission, with the file id; never the file's name or content.
     */
    FILE_UPLOADED("file-uploaded", "file"),
    /**
     * The pipeline rejected a file (REFUSED, with the fixed reject code): malware, a scan that timed out, a disallowed or
     * oversized type, an unreadable file or one that fails its schema. Written by the scan job, so it has no actor.
     */
    FILE_REJECTED("file-rejected", "file"),
    /** Someone but the System's own SMO/Tech Lead read a file's pipeline status, or the list of a VA Submission's files. */
    FILE_VIEWED("file-viewed", "file"),
    /** A clean file's bytes were downloaded, by anyone who may see the System. */
    FILE_DOWNLOADED("file-downloaded", "file"),
    /**
     * Someone who may see the System read the scan date and target a parsed file names. They are report content, so the read
     * is audited for every role, the System's own SMO/Tech Lead included. About the System and the VA Submission, with the
     * file id; never the date or the target.
     */
    FILE_METADATA_VIEWED("file-metadata-viewed", "file"),
    /**
     * Someone who may see the System listed the Findings of a VA Submission. The Findings carry report content, so the read
     * is audited for every role, the System's own SMO/Tech Lead included. One entry per list request, about the System and
     * the VA Submission, with the number of Findings returned; never an identifier, a package or a host.
     */
    FINDINGS_VIEWED("findings-viewed", "database"),
    /**
     * An SMO/Tech Lead proposed a Finding Status (SUCCESS, with the status as a fixed code, and the memo file's id when a
     * Risk Accepted proposal has one, and the batch id when it was made in a Bulk-set batch), or was refused because the
     * VA Submission takes no proposals in its present state (REFUSED, with a fixed reason). About the System, the VA Submission and the Finding; never the evidence,
     * justification, reason, approver, plan or date.
     */
    STATUS_PROPOSED("status-proposed", "database"),
    /**
     * Someone who may see the System read the history of the proposals of one Finding. The proposals carry what the SMO/Tech
     * Lead wrote, so the read is audited for every role. About the System, the VA Submission and the Finding, with the
     * number of proposals returned; never their text.
     */
    FINDING_PROPOSALS_VIEWED("finding-proposals-viewed", "database"),
    /**
     * An SMO/Tech Lead applied one Finding Status to several Findings in one batch (SUCCESS, with the batch id, the number
     * of Findings, the status as a fixed code and the memo file's id when there is one), or was refused because the VA
     * Submission takes no proposals in its present state (REFUSED, with a fixed reason). About the System and the VA
     * Submission; never a Finding's identifier or anything the SMO/Tech Lead wrote.
     */
    BATCH_PROPOSED("batch-proposed", "database"),
    /**
     * A Cyber Manager or VA POC read the progress counts of a VA Submission (story 38), as a read of the VA Submission is
     * recorded. The System's own SMO/Tech Lead reading them is not. About the System and the VA Submission; no detail.
     */
    READINESS_VIEWED("readiness-viewed", "database");

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
