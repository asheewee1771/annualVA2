package sg.annualva.finding;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sg.annualva.access.AuditActor;
import sg.annualva.audit.AuditAction;
import sg.annualva.audit.AuditEvent;
import sg.annualva.audit.AuditLog;
import sg.annualva.audit.AuditOutcome;
import sg.annualva.audit.DetailKey;
import sg.annualva.parser.ReportText;
import sg.annualva.parser.Severity;
import sg.annualva.submission.Submissions;
import sg.annualva.web.RejectedRequestException;
import sg.annualva.web.ResourceNotFoundException;

/**
 * Findings the SMO/Tech Lead types in because the tool cannot export a machine-readable report (story 23, ADR 0030). A
 * Finding entered by hand belongs to a Scan Report marked MANUAL, is kept like a parsed one (it needs a Finding Status, and
 * the Cyber Manager reviews it), has no place, and is always shown as Manually Entered ({@link FindingSource#MANUAL}).
 *
 * <p>Its identity key is {@code manual|<Scan Report>|<identifier>|<package or rule>} (a {@code |} or {@code \} in a
 * value is escaped with {@code \}), so the same Finding is entered once, and it never matches a parsed one.
 */
@Service
public class ManualFindings {

    static final int MAX_TITLE = 200;
    static final int MAX_IDENTIFIER = 200;
    static final int MAX_PACKAGE_OR_RULE = 500;

    private final ScanReports scanReports;
    private final ScanReportStore store;
    private final FindingStore findings;
    private final Proposals proposals;
    private final AuditActor actor;
    private final AuditLog audit;
    private final Clock clock;

    ManualFindings(ScanReports scanReports, ScanReportStore store, FindingStore findings, Proposals proposals,
            AuditActor actor, AuditLog audit, Clock clock) {
        this.scanReports = scanReports;
        this.store = store;
        this.findings = findings;
        this.proposals = proposals;
        this.actor = actor;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Keeps a Finding typed in by the SMO/Tech Lead. The checks run in the order ownership (a VA Submission of a System the
     * caller is not SMO/Tech Lead of is "not found", ADR 0013), the state of the VA Submission (Draft or Returned for
     * Rework), the fields in the order {@code reportType}, {@code title}, {@code identifier}, {@code packageOrRule},
     * {@code severity} (400 {@code invalid-field} naming the first that is wrong; not audited), then, with the Scan Report
     * held, that it is marked for Findings entered by hand, and last that the VA Submission does not have this Finding
     * yet. Each 409 is audited in an entry of its own (ADR 0014); the Finding kept is a {@code FINDING_ENTERED} entry about
     * it, with the Scan Report and nothing that was typed.
     *
     * @throws ResourceNotFoundException for a VA Submission of a System the caller is not SMO/Tech Lead of
     * @throws RejectedRequestException 409 {@code invalid-transition} when the VA Submission takes no changes now, 400
     *     {@code invalid-field} naming the field that is wrong, 409 {@code wrong-report-mode} when the Scan Report is not
     *     marked MANUAL, 409 {@code duplicate-finding} when it already has a Finding with this identifier and package or
     *     rule
     */
    @Transactional
    public FindingView enter(UUID submissionId, ManualFindingRequest request) {
        Submissions.EditTarget target = proposals.editable(submissionId, AuditAction.FINDING_ENTERED, null,
                "This VA Submission takes no new Findings in its present state.");
        ScanReportStore.Manual finding = read(request);
        if (scanReports.lockedMode(target, finding.reportType()) != ReportMode.MANUAL) {
            throw refused(target, "wrong-report-mode",
                    "Findings are entered by hand only for a Scan Report marked for Findings entered by hand.");
        }
        UUID id = store.insertManual(target, finding, clock.instant())
                .orElseThrow(() -> refused(target, "duplicate-finding",
                        "This Scan Report already has a Finding with this identifier and package or rule."));
        audit.record(actor.attribute(AuditEvent.of(AuditAction.FINDING_ENTERED, AuditOutcome.SUCCESS))
                .onSystem(target.systemId()).onSubmission(submissionId).onFinding(id)
                .with(DetailKey.REPORT_TYPE, finding.reportType().name()));
        return new FindingView(id, target.systemId(), finding.reportType(), finding.key(), finding.identifier(),
                finding.packageOrRule(), finding.severity(), false, null, 0, List.of(), null, null, FindingSource.MANUAL,
                finding.title(), null);
    }

    /**
     * Deletes a Finding the SMO/Tech Lead entered by mistake (ticket 60, ADR 0061). Proposals and their history are never
     * deleted, so only one that has never had a proposal goes; one that has had one is given a Finding Status instead. A
     * parsed Finding goes only with the file that reports it. The checks run in the order the Finding and ownership (one
     * that does not exist, or of a System the caller is not SMO/Tech Lead of, is "not found", ADR 0013), the state of the
     * VA Submission (Draft or Returned for Rework), then, with the Finding's row held (a proposal for it waits, or it for
     * the proposal), that it was entered by hand and that it has no proposal history. Each 409 is audited in an entry of
     * its own about the Finding (ADR 0014); the deletion is a {@code FINDING_DELETED} entry with its Scan Report.
     *
     * @throws ResourceNotFoundException for a Finding that does not exist or of a System the caller is not SMO/Tech Lead
     *     of
     * @throws RejectedRequestException 409 {@code invalid-transition} when the VA Submission takes no changes now, 409
     *     {@code not-manually-entered} for a parsed Finding, 409 {@code finding-has-history} for one that has had a proposal
     */
    @Transactional
    public void delete(UUID findingId) {
        ScanReportStore.Entered found = store.entered(findingId).orElseThrow(ResourceNotFoundException::new);
        Submissions.EditTarget target = proposals.editable(found.submissionId(), AuditAction.FINDING_DELETED, findingId,
                "This VA Submission's Findings cannot be changed in its present state.");
        if (!proposals.lock(target, List.of(findingId))) {
            // Located a moment ago in this VA Submission and never moved: it was deleted since.
            throw new ResourceNotFoundException();
        }
        if (found.source() != FindingSource.MANUAL) {
            throw refusedDeletion(target, findingId, "not-manually-entered", "not-manually-entered",
                    "Only a Finding entered by hand can be deleted. A Finding read from a report file goes when that "
                    + "file is deleted.");
        }
        if (findings.removeUnproposed(List.of(findingId)).isEmpty()) {
            // Entered by hand (so it has no place) and held: only a proposal it has had keeps it.
            throw refusedDeletion(target, findingId, "has-history", "finding-has-history",
                    "This Finding has had a proposed Finding Status, and that history is kept. Propose the status that "
                    + "fits it instead.");
        }
        audit.record(actor.attribute(AuditEvent.of(AuditAction.FINDING_DELETED, AuditOutcome.SUCCESS))
                .onSystem(target.systemId()).onSubmission(target.submissionId()).onFinding(findingId)
                .with(DetailKey.REPORT_TYPE, found.reportType().name()));
    }

    /** A refused deletion, audited in an entry of its own about the Finding with a fixed reason. */
    private RejectedRequestException refusedDeletion(Submissions.EditTarget target, UUID findingId, String reason,
            String type, String detail) {
        audit.recordIndependently(actor.attribute(AuditEvent.of(AuditAction.FINDING_DELETED, AuditOutcome.REFUSED))
                .onSystem(target.systemId()).onSubmission(target.submissionId()).onFinding(findingId)
                .with(DetailKey.REASON, reason));
        return new RejectedRequestException(HttpStatus.CONFLICT, type, detail);
    }

    /** Audits a refusal in an entry of its own, then answers 409 with the reason as its type; fixed text only. */
    private RejectedRequestException refused(Submissions.EditTarget target, String reason, String detail) {
        audit.recordIndependently(actor.attribute(AuditEvent.of(AuditAction.FINDING_ENTERED, AuditOutcome.REFUSED))
                .onSystem(target.systemId()).onSubmission(target.submissionId()).with(DetailKey.REASON, reason));
        return new RejectedRequestException(HttpStatus.CONFLICT, reason, detail);
    }

    /** The fields in the order they are checked, each text cleaned to one line and bounded (IM8 as-1). */
    private static ScanReportStore.Manual read(ManualFindingRequest request) {
        ReportType type = choice("reportType", request.reportType(), ReportType.values(),
                "DEPENDENCY_CHECK, SECURE_CONFIGURATION or BURP_SUITE");
        String title = line("title", request.title(), MAX_TITLE);
        String identifier = line("identifier", request.identifier(), MAX_IDENTIFIER);
        String packageOrRule = line("packageOrRule", request.packageOrRule(), MAX_PACKAGE_OR_RULE);
        Severity severity = choice("severity", request.severity(), Severity.values(),
                "CRITICAL, HIGH, MEDIUM, LOW or INFORMATIONAL");
        String key = "manual|" + type.name() + "|" + escaped(identifier) + "|" + escaped(packageOrRule);
        return new ScanReportStore.Manual(type, key, Findings.sha256(key), title, identifier, packageOrRule, severity);
    }

    private static <E extends Enum<E>> E choice(String field, @Nullable String raw, E[] values, String names) {
        if (raw == null) {
            throw RejectedRequestException.invalidField(field, "The field '" + field + "' is required.");
        }
        return Arrays.stream(values).filter(value -> value.name().equals(raw)).findFirst()
                .orElseThrow(() -> RejectedRequestException.invalidField(field,
                        "The field '" + field + "' must be " + names + "."));
    }

    private static String line(String field, @Nullable String raw, int max) {
        return ReportText.oneLine(field, raw, max, RejectedRequestException::invalidField)
                .orElseThrow(() -> RejectedRequestException.invalidField(field, "The field '" + field + "' is required."));
    }

    /** A value of the identity key with its separator escaped, so two different Findings never share a key. */
    private static String escaped(String value) {
        return value.replace("\\", "\\\\").replace("|", "\\|");
    }
}
