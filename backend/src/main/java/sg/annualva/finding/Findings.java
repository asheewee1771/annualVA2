package sg.annualva.finding;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
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
    private final Proposals proposals;
    private final Submissions submissions;
    private final AccessGuard guard;
    private final AuditActor actor;
    private final AuditLog audit;
    private final CycleCalendar calendar;
    private final SubmitChecks checks;

    Findings(FindingStore store, Proposals proposals, Submissions submissions, AccessGuard guard, AuditActor actor,
            AuditLog audit, CycleCalendar calendar, SubmitChecks checks) {
        this.store = store;
        this.proposals = proposals;
        this.submissions = submissions;
        this.guard = guard;
        this.actor = actor;
        this.audit = audit;
        this.calendar = calendar;
        this.checks = checks;
    }

    /**
     * A parsed rescan that replaces a report file (story 39).
     *
     * @param fileId the rescan's own file id
     * @param name the name the person gave it, as shown on the screen; it is named in the evidence of a Remediated proposal
     * @param scannedAt when the rescan's report says the scan was made, or null when it says nothing; its business date is
     *     named in that evidence too
     * @param uploadedAt when it was uploaded (UTC); its business date is named in that evidence
     * @param uploadedBy the SMO/Tech Lead who uploaded it: a rescan proposal is recorded as made by them, and they are the
     *     actor of its audit entry, with {@code proposed_as} RESCAN saying it was made for them
     * @param replaced the parsed report file the rescan replaces, with the name it is shown under
     */
    public record Rescan(
            UUID fileId, String name, @Nullable Instant scannedAt, Instant uploadedAt, UUID uploadedBy,
            ProposalView.ReplacedFile replaced) {}

    /**
     * Keeps what a parser read from one file. It must run in the transaction that records the file as parsed, so a file
     * is never parsed without its Findings or the other way round. A key whose current proposal a rescan made (Remediated,
     * because a rescan reported it nowhere) and that this file reports again loses that proposal as current, which is a
     * {@code PROPOSAL_WITHDRAWN} entry by the person who uploaded the file.
     *
     * @param systemId the System of the VA Submission
     * @param uploadedBy the SMO/Tech Lead who uploaded the file: the actor of any change it makes to a proposal
     * @param now when the file was parsed (the clock bean's instant)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID submissionId, UUID systemId, ReportType reportType, UUID reportFileId, UUID uploadedBy,
            ParsedReport parsed, Instant now) {
        store.serialiseSettlements(submissionId);
        keep(submissionId, systemId, reportType, reportFileId, uploadedBy, parsed, now, List.of(), null);
    }

    /**
     * Moves the Findings from a replaced file to its parsed rescan (story 39), comparing identity keys against the old
     * file's places only. It must run in the transaction that records the rescan as parsed, so the Findings never move
     * without it or the other way round.
     * <ul>
     *   <li>A key the rescan still reports keeps its Finding with its proposals and its review: its places in the old file
     *       are replaced by the rescan's, and its severity is the highest of the places it has now.
     *   <li>A key the rescan no longer reports loses its places in the old file. A Finding then reported by no file gets a
     *       new proposal, Remediated, made as RESCAN, whose evidence names the rescan, the scan date its report gives and
     *       the date it was uploaded, and which names the replaced file; its review goes back to PENDING, so the Cyber
     *       Manager confirms it like any other. A Finding whose current proposal is already Remediated is left as it is.
     *   <li>A new key is a new Finding, with no proposal.
     * </ul>
     * A Finding still reported by another file of the VA Submission keeps those places and its status. The Remediated
     * proposals go through the one proposal write path and audit builder ({@link Proposals#write}), made for the uploader.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void replace(UUID submissionId, UUID systemId, ReportType reportType, Rescan rescan, ParsedReport parsed,
            Instant now) {
        store.serialiseSettlements(submissionId);
        UUID replacedFileId = rescan.replaced().id();
        // The replaced file is PARSED and has this one live rescan, so its places cannot change under the settlement.
        List<UUID> touched = store.findingsOf(replacedFileId);
        keep(submissionId, systemId, reportType, rescan.fileId(), rescan.uploadedBy(), parsed, now, touched,
                replacedFileId);
        List<UUID> gone = store.unreported(touched);
        if (gone.isEmpty()) {
            return;
        }
        Instant scanned = rescan.scannedAt();
        ProposalFields.Parsed remediated = ProposalFields.remediatedByRescan(rescan.name(),
                scanned == null ? null : calendar.dateOf(scanned), calendar.dateOf(rescan.uploadedAt()));
        proposals.write(submissionId, systemId, gone, remediated, null,
                Proposals.Proposer.rescan(rescan.uploadedBy(), rescan.fileId(), rescan.replaced()));
    }

    /**
     * Keeps a file's Findings and places, once the caller holds the VA Submission's settlement lock (so no other settlement
     * of it runs until this one commits). Every existing Finding it will write is locked first, in one statement in id
     * order (the ones with the file's keys and the ones named to lock as well), as every proposal and Bulk-set locks them;
     * new keys go in in hash order with the severity of their places. Then the places change (the file's are added, and a
     * replaced file's removed), and every existing Finding it locked is rated by the places it has now: a Finding's
     * severity is always the highest of its current places (ADR 0022 item 4), and one left with none keeps the severity it
     * was last reported with. A key whose current proposal a rescan made is reported again here, so that proposal no
     * longer holds: the Finding has no current proposal (so no review, a Confirmed one included), and each such Finding is
     * a {@code PROPOSAL_WITHDRAWN} entry by the uploader, with the file's id and {@code proposed_as} RESCAN.
     *
     * @param alsoLock Findings to lock with the file's keys: a replaced file's (none for an ordinary upload)
     * @param replacedFileId the file whose places are removed; null for an ordinary upload
     */
    private void keep(UUID submissionId, UUID systemId, ReportType reportType, UUID reportFileId, UUID uploadedBy,
            ParsedReport parsed, Instant now, List<UUID> alsoLock, @Nullable UUID replacedFileId) {
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
        findings.sort(Comparator.comparing(FindingStore.Keyed::hash));
        List<String> hashes = findings.stream().map(FindingStore.Keyed::hash).toList();
        List<UUID> locked = store.lockForSettlement(submissionId, hashes, alsoLock);
        store.upsert(submissionId, systemId, reportType, findings, now);
        store.addOccurrences(submissionId, reportFileId, places);
        if (replacedFileId != null) {
            store.removeOccurrences(replacedFileId);
        }
        store.rerate(locked);
        for (UUID findingId : store.withdrawRescanProposals(submissionId, hashes)) {
            AuditEvent withdrawn = AuditEvent.of(AuditAction.PROPOSAL_WITHDRAWN, AuditOutcome.SUCCESS);
            audit.record(actor.onBehalfOf(withdrawn, uploadedBy)
                    .onSystem(systemId).onSubmission(submissionId).onFinding(findingId)
                    .with(DetailKey.PROPOSED_AS, ProposedAs.RESCAN.name())
                    .with(DetailKey.FILE_ID, reportFileId));
        }
    }

    /**
     * One page of the VA Submission's Findings that match the filter, for anyone who may see its System, the most severe
     * first. A VA Submission the caller may not see is "not found", the same as one that does not exist (ADR 0013). The
     * total is the number of Findings that match, not of all of them.
     */
    @Transactional
    public Page<FindingView> list(UUID submissionId, FindingFilter filter, int page, int size) {
        UUID systemId = submissions.systemOf(submissionId);
        List<FindingStore.Row> rows = store.page(submissionId, filter, size, (long) page * size);
        Map<UUID, List<FindingView.SourceFile>> sources = store.sources(rows.stream().map(FindingStore.Row::id).toList());
        List<FindingView> items = rows.stream()
                .map(row -> new FindingView(row.id(), row.systemId(), row.reportType(), row.identityKey(),
                        row.identifier(), row.packageOrRule(), row.severity(), row.didNotComplete(), row.confidence(),
                        row.occurrenceCount(), sources.getOrDefault(row.id(), List.of()), row.reviewState(),
                        row.currentProposal(), row.source(), row.title()))
                .toList();
        audit.record(actor.attribute(AuditEvent.of(AuditAction.FINDINGS_VIEWED, AuditOutcome.SUCCESS))
                .onSystem(systemId).onSubmission(submissionId).with(DetailKey.COUNT, items.size()));
        return new Page<>(items, page, size, store.count(submissionId, filter));
    }

    /**
     * The progress counts of a VA Submission (story 38) and the report files whose age stands in the way of submitting
     * (story 26: more than 90 days old at today's business date, or with no scan date to judge), and every reason it cannot
     * be submitted apart from the Declaration ({@link SubmitChecks}, story 40), for anyone who may see its System. They are counts and file names, not report content, so they follow the rule for a read of the VA Submission itself (reviewer decisions, "Reading a single VA
     * Submission is audited unless the owning SMO/Tech Lead reads it"): a read by a Cyber Manager or a VA POC is a
     * {@code READINESS_VIEWED} entry with ids only, and the owner's own reads, which the screen repeats after every
     * proposal, write nothing. One the caller may not see is "not found", the same as one that does not exist.
     */
    @Transactional
    public Readiness readiness(UUID submissionId) {
        UUID systemId = submissions.systemOf(submissionId);
        Readiness readiness = checks.readiness(submissionId);
        if (!guard.scope(Need.SUBMIT).contains(systemId)) {
            audit.record(actor.attribute(AuditEvent.of(AuditAction.READINESS_VIEWED, AuditOutcome.SUCCESS))
                    .onSystem(systemId).onSubmission(submissionId));
        }
        return readiness;
    }

    /**
     * How many Findings of each of these VA Submissions the Cyber Manager has confirmed and how many await their review,
     * for a dashboard (story 48). Only the VA Submissions of Systems the caller may see are answered, and one with no
     * Finding is absent. They are counts, not report content, so like the inbox that shows them (ADR 0019) the read is not
     * audited.
     */
    @Transactional(readOnly = true)
    public Map<UUID, ReviewCounts> reviewCounts(Collection<UUID> submissionIds) {
        var scope = guard.scope(Need.VIEW);
        return store.reviewCounts(List.copyOf(submissionIds)).stream()
                .filter(tally -> scope.contains(tally.systemId()))
                .collect(Collectors.toUnmodifiableMap(FindingStore.ReviewTally::submissionId,
                        FindingStore.ReviewTally::counts));
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
        Submissions.EditTarget target = proposals.target(located.submissionId(), AuditAction.STATUS_PROPOSED, findingId);
        ProposalFields.Parsed fields = proposals.fields(target, request);
        List<UUID> one = List.of(findingId);
        if (!proposals.lock(target, one)) {
            // Located a moment ago in this VA Submission; Findings are never moved or removed.
            throw new ResourceNotFoundException();
        }
        return proposals.write(target.submissionId(), target.systemId(), one, fields, null, proposals.caller())
                .getFirst();
    }

    /**
     * Every proposal of one Finding, the latest first, for anyone who may see its System; the history the Finding keeps.
     * They carry what the SMO/Tech Lead wrote, so the read is audited for every role, with the count only. A Finding the
     * caller may not see is "not found", the same as one that does not exist.
     */
    @Transactional
    public List<ProposalView> history(UUID findingId) {
        FindingStore.Located located = store.locate(findingId).orElseThrow(ResourceNotFoundException::new);
        submissions.systemOf(located.submissionId());
        List<ProposalView> proposals = store.proposals(findingId);
        audit.record(actor.attribute(AuditEvent.of(AuditAction.FINDING_PROPOSALS_VIEWED, AuditOutcome.SUCCESS))
                .onSystem(located.systemId()).onSubmission(located.submissionId()).onFinding(findingId)
                .with(DetailKey.COUNT, proposals.size()));
        return proposals;
    }

    /** The lower-case hex SHA-256 of an identity key: what a VA Submission keys its Findings on. */
    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is available on every Java platform", e);
        }
    }
}
