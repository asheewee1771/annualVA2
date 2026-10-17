package sg.annualva.finding;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import sg.annualva.access.AuditActor;
import sg.annualva.access.CurrentUser;
import sg.annualva.access.Need;
import sg.annualva.audit.AuditAction;
import sg.annualva.audit.AuditEvent;
import sg.annualva.audit.AuditLog;
import sg.annualva.audit.AuditOutcome;
import sg.annualva.audit.DetailKey;
import sg.annualva.cycle.CycleCalendar;
import sg.annualva.parser.ParsedReport;
import sg.annualva.submission.Submissions;
import sg.annualva.web.Page;
import sg.annualva.web.RejectedRequestException;
import sg.annualva.web.ResourceNotFoundException;

/**
 * The Findings of a VA Submission (stories 20a, 25 and 28). A parser reads a Scan Report file into Findings; this keeps
 * them, one per identity key in a VA Submission however many files of the Scan Report name it, with one occurrence for
 * each place it was reported. Reading them back is audited for every role, because they carry report content (reviewer
 * decisions, "Reading a single VA Submission is audited..."): one entry per list request, with the System, the VA
 * Submission and the number returned, and never an identifier, a package or a host.
 *
 * <p>The SMO/Tech Lead proposes a Finding Status for a Finding (story 35): {@link #propose}. Every proposal is a new row and
 * the history is kept ({@link #history}); the text of a proposal is report-adjacent content, so like a Finding it is data
 * and never reaches a log line, an audit entry or an error body.
 */
@Service
public class Findings {

    private final FindingStore store;
    private final Submissions submissions;
    private final CycleCalendar calendar;
    private final CurrentUser user;
    private final AuditActor actor;
    private final AuditLog audit;
    private final Clock clock;

    Findings(FindingStore store, Submissions submissions, CycleCalendar calendar, CurrentUser user, AuditActor actor,
            AuditLog audit, Clock clock) {
        this.store = store;
        this.submissions = submissions;
        this.calendar = calendar;
        this.user = user;
        this.actor = actor;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Keeps what a parser read from one file. It must run in the transaction that records the file as parsed, so a file
     * is never parsed without its Findings or the other way round.
     *
     * @param systemId the System of the VA Submission
     * @param now when the file was parsed (the clock bean's instant)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID submissionId, UUID systemId, ReportType reportType, UUID reportFileId, ParsedReport parsed,
            Instant now) {
        List<FindingStore.Keyed> findings = new ArrayList<>(parsed.findings().size());
        List<FindingStore.Placed> places = new ArrayList<>();
        for (ParsedReport.Finding finding : parsed.findings()) {
            String hash = sha256(finding.identityKey());
            findings.add(new FindingStore.Keyed(finding.identityKey(), hash, finding.identifier(), finding.subject(),
                    finding.severity()));
            finding.occurrences().forEach(
                    place -> places.add(new FindingStore.Placed(hash, place.locator(), place.severity(),
                            place.didNotComplete(), place.confidence())));
        }
        store.upsert(submissionId, systemId, reportType, findings, now);
        store.addOccurrences(submissionId, reportFileId, places);
    }

    /**
     * One page of the VA Submission's Findings that match the filter, for anyone who may see its System, the most severe
     * first. A VA Submission the caller may not see is "not found", the same as one that does not exist (ADR 0013). The
     * total is the number of Findings that match, not of all of them.
     */
    @Transactional
    public Page<FindingView> list(UUID submissionId, FindingFilter filter, int page, int size) {
        UUID systemId = submissions.systemOf(submissionId, Need.VIEW);
        List<FindingStore.Row> rows = store.page(submissionId, filter, size, (long) page * size);
        Map<UUID, List<FindingView.SourceFile>> sources = store.sources(rows.stream().map(FindingStore.Row::id).toList());
        List<FindingView> items = rows.stream()
                .map(row -> new FindingView(row.id(), row.systemId(), row.reportType(), row.identityKey(),
                        row.identifier(), row.packageOrRule(), row.severity(), row.didNotComplete(), row.confidence(),
                        row.occurrenceCount(), sources.getOrDefault(row.id(), List.of()), row.reviewState(),
                        row.currentProposal()))
                .toList();
        audit.record(actor.attribute(AuditEvent.of(AuditAction.FINDINGS_VIEWED, AuditOutcome.SUCCESS))
                .onSystem(systemId).onSubmission(submissionId).with(DetailKey.COUNT, items.size()));
        return new Page<>(items, page, size, store.count(submissionId, filter));
    }

    /**
     * Proposes a Finding Status for a Finding of a System the caller is SMO/Tech Lead of (story 35). The proposal is a new
     * row, never a change to an earlier one: it becomes the Finding's current proposal and the review state goes back to
     * PENDING. Each command checks, in this order: ownership (so a Finding of a System the caller may not reach is "not
     * found", ADR 0013), the state of the VA Submission, then the fields, and only then writes, in one transaction with the
     * audit entry. The refusal for the state is audited in an entry of its own (ADR 0014); a field that is wrong is a 400
     * and writes nothing.
     *
     * @throws ResourceNotFoundException for a Finding that does not exist or of a System the caller is not SMO/Tech Lead of
     * @throws RejectedRequestException 409 {@code invalid-transition} when the VA Submission is not in Draft or Returned
     *     for Rework; 400 {@code invalid-field} (naming the field) when the status or a field of it is wrong, or the memo
     *     is not a supporting evidence file of this VA Submission that has finished its checks
     */
    @Transactional
    public ProposalView propose(UUID findingId, ProposalRequest request) {
        FindingStore.Located located = store.lock(findingId).orElseThrow(ResourceNotFoundException::new);
        // The files and the Findings are edited in the same states: the SMO/Tech Lead is still preparing the VA Submission.
        Submissions.FileTarget target = submissions.fileTarget(located.submissionId(), Need.SUBMIT);
        if (!target.acceptsFiles()) {
            audit.recordIndependently(actor.attribute(AuditEvent.of(AuditAction.STATUS_PROPOSED, AuditOutcome.REFUSED))
                    .onSystem(located.systemId()).onSubmission(located.submissionId()).onFinding(findingId)
                    .with(DetailKey.REASON, "state-not-editable"));
            throw new RejectedRequestException(HttpStatus.CONFLICT, "invalid-transition",
                    "This VA Submission takes no new proposals in its present state.");
        }
        ProposalFields.Parsed fields = ProposalFields.parse(request, calendar.today());
        UUID memo = fields.approvalMemoFileId();
        if (memo != null && !store.isReadyEvidence(memo, located.submissionId())) {
            throw RejectedRequestException.invalidField("approvalMemoFileId",
                    "The field 'approvalMemoFileId' must be a supporting evidence file of this VA Submission that has finished its checks.");
        }
        ProposalView proposal = store.propose(findingId, fields, user.id(), clock.instant());
        AuditEvent event = actor.attribute(AuditEvent.of(AuditAction.STATUS_PROPOSED, AuditOutcome.SUCCESS))
                .onSystem(located.systemId()).onSubmission(located.submissionId()).onFinding(findingId)
                .with(DetailKey.FINDING_STATUS, fields.status().name());
        audit.record(memo == null ? event : event.with(DetailKey.FILE_ID, memo));
        return proposal;
    }

    /**
     * Every proposal of one Finding, the latest first, for anyone who may see its System; the history the Finding keeps.
     * They carry what the SMO/Tech Lead wrote, so the read is audited for every role, with the count only. A Finding the
     * caller may not see is "not found", the same as one that does not exist.
     */
    @Transactional
    public List<ProposalView> history(UUID findingId) {
        FindingStore.Located located = store.locate(findingId).orElseThrow(ResourceNotFoundException::new);
        submissions.systemOf(located.submissionId(), Need.VIEW);
        List<ProposalView> proposals = store.proposals(findingId);
        audit.record(actor.attribute(AuditEvent.of(AuditAction.FINDING_PROPOSALS_VIEWED, AuditOutcome.SUCCESS))
                .onSystem(located.systemId()).onSubmission(located.submissionId()).onFinding(findingId)
                .with(DetailKey.COUNT, proposals.size()));
        return proposals;
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is available on every Java platform", e);
        }
    }
}
