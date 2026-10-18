package sg.annualva.finding;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import sg.annualva.access.AuditActor;
import sg.annualva.access.Need;
import sg.annualva.audit.AuditAction;
import sg.annualva.audit.AuditEvent;
import sg.annualva.audit.AuditLog;
import sg.annualva.audit.AuditOutcome;
import sg.annualva.audit.DetailKey;
import sg.annualva.parser.ReportText;
import sg.annualva.submission.Submissions;
import sg.annualva.web.RejectedRequestException;
import sg.annualva.web.ResourceNotFoundException;

/**
 * How the three Scan Reports of a VA Submission are provided (story 23, ADR 0030): uploaded (the default), not applicable
 * (Dependency-Check or Burp Suite, with a justification the Cyber Manager reviews like a Finding) or entered by hand (with
 * a justification). Secure Configuration is always required. A Scan Report not uploaded takes no report file, and one not
 * entered by hand takes no Finding typed in; supporting evidence goes to any of them. A mode change is allowed only while
 * the Scan Report holds no report file and no Finding, so nothing is ever left behind under a mode that denies it.
 *
 * <p>Every write to a Scan Report (a mode change, a report file, a Finding by hand) locks the Scan Report's row first: a
 * mode change exclusively, the others shared, so a mode change and an upload or an entry by hand wait for each other and
 * the second sees what the first did.
 */
@Service
public class ScanReports {

    static final int MAX_JUSTIFICATION = ProposalFields.MAX_TEXT;

    private static final String MODE = "mode";
    private static final String JUSTIFICATION = "justification";

    private final ScanReportStore store;
    private final Proposals proposals;
    private final Submissions submissions;
    private final AuditActor actor;
    private final AuditLog audit;

    ScanReports(ScanReportStore store, Proposals proposals, Submissions submissions, AuditActor actor, AuditLog audit) {
        this.store = store;
        this.proposals = proposals;
        this.submissions = submissions;
        this.actor = actor;
        this.audit = audit;
    }

    /**
     * The three Scan Reports, in the order Dependency-Check, Secure Configuration, Burp Suite, for anyone who may see the
     * System. The justifications are what a person wrote, so the read is audited for every role, with the count only. A VA
     * Submission the caller may not see is "not found", the same as one that does not exist (ADR 0013).
     */
    @Transactional
    public List<ScanReportView> list(UUID submissionId) {
        UUID systemId = submissions.systemOf(submissionId, Need.VIEW);
        Map<ReportType, ScanReportStore.Slot> slots = store.ofSubmission(submissionId);
        List<ScanReportView> reports = Arrays.stream(ReportType.values())
                .map(type -> view(type, slots.getOrDefault(type, ScanReportStore.Slot.UPLOADED)))
                .toList();
        audit.record(actor.attribute(AuditEvent.of(AuditAction.SCAN_REPORTS_VIEWED, AuditOutcome.SUCCESS))
                .onSystem(systemId).onSubmission(submissionId).with(DetailKey.COUNT, reports.size()));
        return reports;
    }

    /**
     * Sets how a Scan Report is provided. The checks run in the order ownership (a VA Submission of a System the caller is
     * not SMO/Tech Lead of is "not found"), the state of the VA Submission (Draft or Returned for Rework; the refusal is
     * audited, ADR 0014), the mode and the justification (400 {@code invalid-field} naming the field; not audited), and
     * last, with the Scan Report locked, that a change of mode leaves nothing behind. Setting the mode it already has is
     * always allowed: for a justification it only replaces the text, and a not-applicable justification that changes goes
     * back to PENDING for the Cyber Manager, while the same one keeps its review.
     *
     * @throws ResourceNotFoundException for a VA Submission of a System the caller is not SMO/Tech Lead of
     * @throws RejectedRequestException 409 {@code invalid-transition} when the VA Submission takes no changes now; 400
     *     {@code invalid-field} naming {@code mode} (missing, unknown, or NOT_APPLICABLE for Secure Configuration) or
     *     {@code justification} (missing, empty once cleaned, longer than {@value #MAX_JUSTIFICATION} characters, or given
     *     with UPLOAD); 409 {@code report-not-empty} for a change of mode while the Scan Report holds a report file that
     *     was not rejected or a Finding
     */
    @Transactional
    public ScanReportView setMode(UUID submissionId, ReportType type, ReportModeRequest request) {
        Submissions.FileTarget target = proposals.editable(submissionId, AuditAction.REPORT_MODE_SET, null,
                "This VA Submission's Scan Reports cannot be changed in its present state.");
        ReportMode mode = mode(type, request.mode());
        String justification = justification(mode, request.justification());
        ScanReportStore.Slot current = store.lock(submissionId, type, true);
        if (current.mode() != mode && store.hasContent(submissionId, type)) {
            audit.recordIndependently(actor.attribute(AuditEvent.of(AuditAction.REPORT_MODE_SET, AuditOutcome.REFUSED))
                    .onSystem(target.systemId()).onSubmission(submissionId).with(DetailKey.REASON, "report-not-empty"));
            throw new RejectedRequestException(HttpStatus.CONFLICT, "report-not-empty",
                    "This Scan Report already holds a report file or a Finding, so how it is provided cannot change.");
        }
        ScanReportStore.Slot slot = new ScanReportStore.Slot(mode, justification, review(mode, justification, current));
        store.set(submissionId, type, slot);
        audit.record(actor.attribute(AuditEvent.of(AuditAction.REPORT_MODE_SET, AuditOutcome.SUCCESS))
                .onSystem(target.systemId()).onSubmission(submissionId)
                .with(DetailKey.REPORT_TYPE, type.name()).with(DetailKey.MODE, mode.name()));
        return view(type, slot);
    }

    /**
     * Whether the Scan Report takes report files: it is uploaded. Holds the Scan Report's row until the caller's
     * transaction ends, so its mode cannot change before the file is kept; the upload's transaction must call it before
     * it keeps the file.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean takesReportFiles(UUID submissionId, ReportType type) {
        return store.lock(submissionId, type, false).mode() == ReportMode.UPLOAD;
    }

    /** The mode of a Scan Report, its row held shared until the caller's transaction ends (a Finding typed in). */
    ReportMode lockedMode(UUID submissionId, ReportType type) {
        return store.lock(submissionId, type, false).mode();
    }

    /** A not-applicable justification is reviewed: a new one awaits the Cyber Manager, the same one keeps its review. */
    private static @Nullable ReviewState review(ReportMode mode, @Nullable String justification,
            ScanReportStore.Slot current) {
        if (mode != ReportMode.NOT_APPLICABLE) {
            return null;
        }
        boolean unchanged = current.mode() == mode && Objects.equals(current.justification(), justification);
        return unchanged ? current.reviewState() : ReviewState.PENDING;
    }

    private static ReportMode mode(ReportType type, @Nullable String raw) {
        if (raw == null) {
            throw RejectedRequestException.invalidField(MODE, "The field 'mode' is required.");
        }
        ReportMode mode = Arrays.stream(ReportMode.values()).filter(each -> each.name().equals(raw)).findFirst()
                .orElseThrow(() -> RejectedRequestException.invalidField(MODE,
                        "The field 'mode' must be UPLOAD, NOT_APPLICABLE or MANUAL."));
        if (mode == ReportMode.NOT_APPLICABLE && type == ReportType.SECURE_CONFIGURATION) {
            throw RejectedRequestException.invalidField(MODE,
                    "The field 'mode' cannot be NOT_APPLICABLE: Secure Configuration is always required, uploaded or "
                    + "entered by hand.");
        }
        return mode;
    }

    /** The justification, cleaned and bounded as every long text a person types (ADR 0027 item 5); none for UPLOAD. */
    private static @Nullable String justification(ReportMode mode, @Nullable String raw) {
        if (mode == ReportMode.UPLOAD) {
            if (raw != null) {
                throw RejectedRequestException.invalidField(JUSTIFICATION,
                        "The field 'justification' is not used with UPLOAD.");
            }
            return null;
        }
        String cleaned = raw == null ? "" : ReportText.cleanLongText(raw);
        if (cleaned.isEmpty()) {
            throw RejectedRequestException.invalidField(JUSTIFICATION,
                    "The field 'justification' is required for NOT_APPLICABLE and MANUAL.");
        }
        if (!ReportText.fits(cleaned, MAX_JUSTIFICATION)) {
            throw RejectedRequestException.invalidField(JUSTIFICATION,
                    "The field 'justification' must be at most " + MAX_JUSTIFICATION + " characters.");
        }
        return cleaned;
    }

    private static ScanReportView view(ReportType type, ScanReportStore.Slot slot) {
        return new ScanReportView(type, slot.mode(), slot.justification(), slot.reviewState());
    }
}
