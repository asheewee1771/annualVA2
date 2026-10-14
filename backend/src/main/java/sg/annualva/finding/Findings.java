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

/**
 * The Findings of a VA Submission (stories 20a, 25 and 28). A parser reads a Scan Report file into Findings; this keeps
 * them, one per identity key in a VA Submission however many files of the Scan Report name it, with one occurrence for
 * each place it was reported. Reading them back is audited for every role, because they carry report content (reviewer
 * decisions, "Reading a single VA Submission is audited..."): one entry per list request, with the System, the VA
 * Submission and the number returned, and never an identifier, a package or a host.
 */
@Service
public class Findings {

    private final FindingStore store;
    private final Submissions submissions;
    private final AuditActor actor;
    private final AuditLog audit;

    Findings(FindingStore store, Submissions submissions, AuditActor actor, AuditLog audit) {
        this.store = store;
        this.submissions = submissions;
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
                            place.didNotComplete())));
        }
        store.upsert(submissionId, systemId, reportType, findings, now);
        store.addOccurrences(submissionId, reportFileId, places);
    }

    /**
     * One page of the VA Submission's Findings for anyone who may see its System, the most severe first. A VA Submission
     * the caller may not see is "not found", the same as one that does not exist (ADR 0013).
     */
    @Transactional
    public Page<FindingView> list(UUID submissionId, int page, int size) {
        UUID systemId = submissions.systemOf(submissionId, Need.VIEW);
        List<FindingStore.Row> rows = store.page(submissionId, size, (long) page * size);
        Map<UUID, List<FindingView.SourceFile>> sources = store.sources(rows.stream().map(FindingStore.Row::id).toList());
        List<FindingView> items = rows.stream()
                .map(row -> new FindingView(row.id(), row.systemId(), row.reportType(), row.identityKey(),
                        row.identifier(), row.packageOrRule(), row.severity(), row.didNotComplete(), row.occurrenceCount(),
                        sources.getOrDefault(row.id(), List.of())))
                .toList();
        audit.record(actor.attribute(AuditEvent.of(AuditAction.FINDINGS_VIEWED, AuditOutcome.SUCCESS))
                .onSystem(systemId).onSubmission(submissionId).with(DetailKey.COUNT, items.size()));
        return new Page<>(items, page, size, store.count(submissionId));
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is available on every Java platform", e);
        }
    }
}
