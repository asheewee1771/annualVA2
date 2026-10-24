package sg.annualva.submission;

import java.util.List;
import java.util.UUID;

/**
 * How far the Cyber Manager's review of a VA Submission is, for the commands that move it out of In Review (stories 55 and
 * 57). The Findings and the not-applicable justifications are kept by the module above this one, so it implements this,
 * and the return and the pass to endorsement read it under their exclusive lock on the VA Submission, which every review
 * waits for (ADR 0035 item 3), without reaching up into it. Ids, fixed codes and counts only, never report content.
 */
public interface ReviewProgress {

    /** Whether it may be returned and what stands in the way of passing it on, by one rule. Runs in the caller's transaction. */
    Outcome outcome(UUID submissionId);

    /** What is Disputed now, each item named by the review that Disputed it. Runs in the caller's transaction. */
    Disputed disputed(UUID submissionId);

    /**
     * Where the review stands.
     *
     * @param returnable whether a Finding or a not-applicable justification is Disputed, so it may be returned for rework
     * @param endorsementBlockers every reason it cannot pass to endorsement, in a fixed order (Findings awaiting review,
     *     Findings Disputed, then each not-applicable justification awaiting review or Disputed), each a
     *     {@link SubmitReadiness.Blocker} naming no file; empty once every Finding and justification is confirmed
     */
    record Outcome(boolean returnable, List<SubmitReadiness.Blocker> endorsementBlockers) {

        public Outcome {
            endorsementBlockers = List.copyOf(endorsementBlockers);
        }
    }

    /**
     * What is Disputed: the Findings in the order they were read, then the not-applicable justifications in Scan Report
     * order.
     */
    record Disputed(List<DisputedFinding> findings, List<DisputedReport> reports) {

        /** Nothing Disputed: what a VA POC's return attaches, as nothing is Disputed Awaiting Endorsement. */
        static final Disputed NONE = new Disputed(List.of(), List.of());

        public Disputed {
            findings = List.copyOf(findings);
            reports = List.copyOf(reports);
        }

        /** How many items are Disputed. */
        public int count() {
            return findings.size() + reports.size();
        }
    }

    /** A Disputed Finding and the review that Disputed its current proposal. */
    record DisputedFinding(UUID findingId, UUID reviewId) {}

    /**
     * A Disputed not-applicable justification, by its Scan Report (the name of the Findings module's {@code ReportType},
     * as text because that type lives above this module), and the review that Disputed it.
     */
    record DisputedReport(String reportType, UUID reviewId) {}
}
