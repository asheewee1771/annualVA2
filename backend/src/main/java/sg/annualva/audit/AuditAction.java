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
     * An SMO/Tech Lead started the VA Submission for a System (SUCCESS), or was refused because it was already started,
     * its Quarter is Handled outside the app, or segregation of duties does not allow it because they are also the
     * System's Cyber Manager (REFUSED, with a fixed reason: {@code sod-conflict} for the last). It is about the System
     * and, on success, the VA Submission; it holds no text from the request.
     */
    SUBMISSION_STARTED("submission-started", "database"),
    /**
     * A Cyber Manager or VA POC read one VA Submission (story 84). The System's own SMO/Tech Lead reading it is not
     * recorded. It is about the System and the VA Submission and holds no detail.
     */
    SUBMISSION_VIEWED("submission-viewed", "database"),
    /**
     * An SMO/Tech Lead submitted a VA Submission for review (story 40; SUCCESS, with {@code state_from}, {@code state_to}
     * and the {@code declaration_version} accepted), or was refused because segregation of duties does not allow the
     * caller (they acted at, or are assigned to, the reviewing step: {@code sod-conflict}), it is not in Draft or Returned
     * for Rework, or something stands in the way of submitting it (REFUSED, with a fixed reason and, for the last, the
     * {@code count} of reasons). About the System and the VA Submission; never the Declaration wording or a file name.
     */
    SUBMISSION_SUBMITTED("submission-submitted", "database"),
    /**
     * The Overdue sweep flagged a VA Submission, or a System with none (Not Started), Overdue because its window passed
     * (stories 42 and 71; SUCCESS, with the {@code state} it was in). Written by the scheduled job, so it has no actor.
     * About the System and, when there is one, the VA Submission.
     */
    SUBMISSION_OVERDUE("submission-overdue", "database"),
    /**
     * An SMO/Tech Lead uploaded a Scan Report file and it was quarantined (SUCCESS), or the upload was refused because the
     * VA Submission takes no files now or the Scan Report is full (REFUSED, with a fixed reason). A rescan (story 39) also
     * names the file it replaces ({@code replaced_file_id}), and its refusals say why the file could not be replaced. About
     * the System and the VA Submission, with the file id; never the file's name or content.
     */
    FILE_UPLOADED("file-uploaded", "file"),
    /**
     * The pipeline rejected a file (REFUSED, with the fixed reject code): malware, a scan that timed out, a disallowed or
     * oversized type, an unreadable file or one that fails its schema. Written by the scan job, so it has no actor.
     */
    FILE_REJECTED("file-rejected", "file"),
    /**
     * A rescan replaced a parsed report file (story 39): it was parsed, the Findings moved to it and the old file is
     * superseded. Written by the scan job for the SMO/Tech Lead who uploaded the rescan, who is its actor; the upload of
     * the rescan is their own {@code FILE_UPLOADED} entry. About the System and the VA Submission, with the rescan's {@code file_id} and the
     * {@code replaced_file_id}; never a name or content.
     */
    FILE_REPLACED("file-replaced", "file"),
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
     * An SMO/Tech Lead confirmed or corrected the scan date and target of a parsed report file (story 25; SUCCESS), or was
     * refused because the VA Submission takes no changes in its present state or a rescan has replaced the file (REFUSED,
     * with a fixed reason). About the System and the VA Submission, with the file id and, on SUCCESS, whether the date and
     * the target differ from the file's ({@code date_changed}, {@code target_changed}); never the date or the target.
     */
    FILE_METADATA_CONFIRMED("file-metadata-confirmed", "file"),
    /**
     * Someone who may see the System listed the Findings of a VA Submission. The Findings carry report content, so the read
     * is audited for every role, the System's own SMO/Tech Lead included. One entry per list request, about the System and
     * the VA Submission, with the number of Findings returned; never an identifier, a package or a host.
     */
    FINDINGS_VIEWED("findings-viewed", "database"),
    /**
     * An SMO/Tech Lead proposed a Finding Status (SUCCESS, with the status as a fixed code, and the memo file's id when a
     * Risk Accepted proposal has one, and the batch id when it was made in a Bulk-set batch), or was refused because the
     * VA Submission takes no proposals in its present state (REFUSED, with a fixed reason). A Remediated proposal a rescan
     * makes (story 39) is by the SMO/Tech Lead who uploaded the rescan, with {@code rescan_file_id} and {@code replaced_file_id}; every entry
     * carries {@code proposed_as}. About the System, the VA Submission and the Finding; never the evidence,
     * justification, reason, approver, plan or date.
     */
    STATUS_PROPOSED("status-proposed", "database"),
    /**
     * A file reported again a Finding whose current proposal a rescan had made (Remediated, because the rescan reported it
     * nowhere), so that proposal is no longer current: the Finding has no current proposal and no review (a Confirmed one
     * included), and the proposal stays in its history (story 39). Written by the scan job for the SMO/Tech Lead who
     * uploaded the file, who is its actor. About the System, the VA Submission and the Finding, with the file's
     * {@code file_id} and {@code proposed_as} RESCAN; never any text.
     */
    PROPOSAL_WITHDRAWN("proposal-withdrawn", "database"),
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
     * Someone who may see the System listed the Bulk-set batches of a VA Submission (story 51). Each carries what the
     * SMO/Tech Lead wrote, so the read is audited for every role. About the System and the VA Submission, with the number of
     * batches; never their text.
     */
    BATCHES_VIEWED("batches-viewed", "database"),
    /**
     * A Cyber Manager or VA POC read the progress counts of a VA Submission (story 38), as a read of the VA Submission is
     * recorded. The System's own SMO/Tech Lead reading them is not. About the System and the VA Submission; no detail.
     */
    READINESS_VIEWED("readiness-viewed", "database"),
    /**
     * An SMO/Tech Lead set how a Scan Report is provided (story 23): uploaded, not applicable or entered by hand (SUCCESS,
     * with {@code report_type} and {@code mode}), or was refused because the VA Submission takes no changes in its present
     * state or the Scan Report already holds a report file or a Finding (REFUSED, with a fixed reason). About the System
     * and the VA Submission; never the justification.
     */
    REPORT_MODE_SET("report-mode-set", "database"),
    /**
     * Someone who may see the System read how its Scan Reports are provided. A justification is what a person wrote, so the
     * read is audited for every role, the System's own SMO/Tech Lead included. About the System and the VA Submission, with
     * the number of Scan Reports; never a justification.
     */
    SCAN_REPORTS_VIEWED("scan-reports-viewed", "database"),
    /**
     * An SMO/Tech Lead entered a Finding by hand (story 23; SUCCESS, about the new Finding, with {@code report_type}), or
     * was refused because the VA Submission takes no changes in its present state, the Scan Report is not marked for
     * Findings entered by hand, or it already has that Finding (REFUSED, with a fixed reason). About the System and the VA
     * Submission; never the title, identifier, package or rule.
     */
    FINDING_ENTERED("finding-entered", "database"),
    /**
     * A Cyber Manager confirmed or Disputed the current proposal of a Finding (story 50; SUCCESS, with the
     * {@code decision}), or was refused because segregation of duties does not allow them to review this VA Submission, it
     * is not In Review, or the proposal they reviewed is no longer the current one (REFUSED, with a fixed reason). About
     * the System, the VA Submission and the Finding; never the comment.
     */
    FINDING_REVIEWED("finding-reviewed", "database"),
    /**
     * A Cyber Manager confirmed or Disputed the justification of a not-applicable Scan Report (story 50; SUCCESS, with
     * {@code report_type} and the {@code decision}), or was refused because segregation of duties does not allow them to
     * review this VA Submission, it is not In Review, the Scan Report is not marked not applicable, or its justification
     * has changed since the version reviewed (REFUSED, with {@code report_type} and a fixed reason). About the System and
     * the VA Submission; never the comment or the justification.
     */
    REPORT_REVIEWED("report-reviewed", "database"),
    /**
     * A Cyber Manager confirmed or Disputed a Bulk-set batch as a whole (story 51; SUCCESS, with the {@code batch_id}, the
     * {@code count} of Findings it decided and the {@code decision}; each of them is also a {@code FINDING_REVIEWED} entry
     * with the {@code batch_id}), or was refused because segregation of duties does not allow them to review this VA
     * Submission, it is not In Review, or no member awaited review (REFUSED, with the {@code batch_id} and a fixed reason).
     * About the System and the VA Submission; never the comment.
     */
    BATCH_REVIEWED("batch-reviewed", "database"),
    /**
     * A Cyber Manager returned a VA Submission In Review for rework (SUCCESS, with {@code state_from}, {@code state_to} and
     * the {@code count} of Disputed items attached), or was refused: segregation of duties, it is not In Review, or nothing
     * is Disputed (REFUSED, with a fixed reason). About the System and the VA Submission; never the comment.
     */
    SUBMISSION_RETURNED("submission-returned", "database"),
    /**
     * A Cyber Manager passed a VA Submission to endorsement (SUCCESS, with {@code state_from} and {@code state_to}), or was
     * refused: segregation of duties, it is not In Review, or something is still unconfirmed (REFUSED, with a fixed reason,
     * and for {@code not-confirmed} the {@code count} of reasons). About the System and the VA Submission.
     */
    SUBMISSION_PASSED("submission-passed-to-endorsement", "database"),
    /** Someone read the returns of a VA Submission, which carry what a person wrote. About the System and the VA Submission. */
    SUBMISSION_RETURNS_VIEWED("submission-returns-viewed", "database"),
    /**
     * An SMO/Tech Lead read the rework queue of their VA Submission (story 60), which carries the Cyber Manager's Dispute
     * comments and what was proposed, so every read is audited. About the System and the VA Submission, with the number
     * of Findings returned; never their text.
     */
    REWORK_QUEUE_VIEWED("rework-queue-viewed", "database"),
    /**
     * A VA POC endorsed a VA Submission Awaiting Endorsement (stories 65 and 67; SUCCESS, with {@code state_from},
     * {@code state_to} and whether it was {@code endorsed_late}), or was refused: segregation of duties, or it is not
     * Awaiting Endorsement (REFUSED, with a fixed reason). A refusal for want of a fresh sign-in is the
     * {@code REQUEST_REFUSED} entry of every step-up. About the System and the VA Submission.
     */
    SUBMISSION_ENDORSED("submission-endorsed", "database"),
    /**
     * The lifecycle job recorded that a VA Cycle's Q1 has started (ticket 41; SUCCESS, with the {@code cycle}). Written
     * by the job, so it has no actor; once per Cycle.
     */
    CYCLE_OPENED("cycle-opened", "configuration"),
    /**
     * A VA Cycle was closed (stories 17 and 68; SUCCESS, with the {@code cycle} and the {@code count} of VA Submissions
     * and Systems made Not Endorsed): by a VA POC, early, who is its actor, or by the lifecycle job the day after its close
     * date, with no actor. A VA POC's close is refused while the Cycle's Q4 is still running or once it is closed
     * (REFUSED, with the {@code cycle} and a fixed reason); a refusal for want of a fresh sign-in is the
     * {@code REQUEST_REFUSED} entry of every step-up.
     */
    CYCLE_CLOSED("cycle-closed", "configuration"),
    /**
     * A VA Cycle's close made a VA Submission, or a System with none (Not Started, which gets a Not Endorsed row of its
     * own), Not Endorsed (story 68; SUCCESS, with {@code state_from}, {@code NOT_STARTED} for a System with none,
     * {@code state_to} and the {@code cycle}). Attributed to the VA POC who closed the Cycle, or to nobody for the
     * lifecycle job. About the System and the VA Submission.
     */
    SUBMISSION_NOT_ENDORSED("submission-not-endorsed", "database"),
    /**
     * An SMO/Tech Lead requested an Exemption and the VA Submission froze (stories 43 and 44; SUCCESS, with
     * {@code state_from}, {@code NOT_STARTED} when the request created it, {@code state_to}, the {@code exemption_id} and
     * the {@code basis}), or was refused: segregation of duties, the state, the Cycle, or a file still being checked
     * (REFUSED, with a fixed reason). About the System and the VA Submission; never the evidence.
     */
    EXEMPTION_REQUESTED("exemption-requested", "database"),
    /**
     * A VA POC approved an Exemption and the VA Submission is Exempted (story 46; SUCCESS, with {@code state_from},
     * {@code state_to} and the {@code exemption_id}), or was refused: segregation of duties, the state, or a request
     * already decided (REFUSED, with the {@code exemption_id} and a fixed reason). A refusal for want of a fresh sign-in is
     * the {@code REQUEST_REFUSED} entry of every step-up. About the System and the VA Submission.
     */
    EXEMPTION_APPROVED("exemption-approved", "database"),
    /**
     * A VA POC rejected an Exemption and the VA Submission went back to the state it was in (story 46; SUCCESS, with
     * {@code state_from}, {@code state_to} and the {@code exemption_id}), or was refused as an approval is. About the
     * System and the VA Submission.
     */
    EXEMPTION_REJECTED("exemption-rejected", "database"),
    /**
     * A VA POC read the pending Exemption requests (story 45), which carry the evidence an SMO/Tech Lead wrote, so every
     * read is audited, with the {@code count} of requests listed; never their text.
     */
    EXEMPTIONS_VIEWED("exemptions-viewed", "database");

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
