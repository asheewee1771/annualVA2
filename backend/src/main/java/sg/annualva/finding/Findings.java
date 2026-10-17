package sg.annualva.finding;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import sg.annualva.access.AccessGuard;
import sg.annualva.access.AuditActor;
import sg.annualva.access.Need;
import sg.annualva.audit.AuditAction;
import sg.annualva.audit.AuditEvent;
import sg.annualva.audit.AuditLog;
import sg.annualva.audit.AuditOutcome;
import sg.annualva.audit.DetailKey;
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
    private final Proposals proposals;
    private final Submissions submissions;
    private final AccessGuard guard;
    private final AuditActor actor;
    private final AuditLog audit;

    Findings(FindingStore store, Proposals proposals, Submissions submissions, AccessGuard guard, AuditActor actor,
            AuditLog audit) {
        this.store = store;
        this.proposals = proposals;
        this.submissions = submissions;
        this.guard = guard;
        this.actor = actor;
        this.audit = audit;
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
     * The progress counts of a VA Submission (story 38), for anyone who may see its System. They are counts, not report
     * content, so they follow the rule for a read of the VA Submission itself (reviewer decisions, "Reading a single VA
     * Submission is audited unless the owning SMO/Tech Lead reads it"): a read by a Cyber Manager or a VA POC is a
     * {@code READINESS_VIEWED} entry with ids only, and the owner's own reads, which the screen repeats after every
     * proposal, write nothing. One the caller may not see is "not found", the same as one that does not exist.
     */
    @Transactional
    public Readiness readiness(UUID submissionId) {
        UUID systemId = submissions.systemOf(submissionId, Need.VIEW);
        Readiness readiness = store.readiness(submissionId);
        if (!guard.scope(Need.SUBMIT).contains(systemId)) {
            audit.record(actor.attribute(AuditEvent.of(AuditAction.READINESS_VIEWED, AuditOutcome.SUCCESS))
                    .onSystem(systemId).onSubmission(submissionId));
        }
        return readiness;
    }

    /**
     * Proposes a Finding Status for a Finding of a System the caller is SMO/Tech Lead of (story 35). The proposal is a new
     * row, never a change to an earlier one: it becomes the Finding's current proposal and the review state goes back to
     * PENDING. Each command checks, in this order: ownership (so a Finding of a System the caller may not reach is "not
     * found", ADR 0013), the state of the VA Submission, then the fields, and only then writes, in one transaction with the
     * audit entry. The refusal for the state is audited in an entry of its own (ADR 0014); a field that is wrong is a 400
     * and writes nothing. The steps are the ones a Bulk-set runs ({@link Proposals}): a proposal for one Finding is a
     * batch of one with no batch row. The Finding's row is locked only once the checks have passed.
     *
     * @throws ResourceNotFoundException for a Finding that does not exist or of a System the caller is not SMO/Tech Lead of
     * @throws RejectedRequestException 409 {@code invalid-transition} when the VA Submission is not in Draft or Returned
     *     for Rework; 400 {@code invalid-field} (naming the field) when the status or a field of it is wrong, or the memo
     *     is not a supporting evidence file of this VA Submission that has finished its checks
     */
    @Transactional
    public ProposalView propose(UUID findingId, ProposalRequest request) {
        FindingStore.Located located = store.locate(findingId).orElseThrow(ResourceNotFoundException::new);
        Submissions.FileTarget target = proposals.target(located.submissionId(), AuditAction.STATUS_PROPOSED, findingId);
        ProposalFields.Parsed fields = proposals.fields(target, request);
        List<UUID> one = List.of(findingId);
        if (!proposals.lock(target, one)) {
            // Located a moment ago in this VA Submission; Findings are never moved or removed.
            throw new ResourceNotFoundException();
        }
        return proposals.write(target, one, fields, null).getFirst();
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
