package sg.annualva.submission;

import org.springframework.stereotype.Component;
import sg.annualva.monitoring.KeySignals;

/**
 * The Overdue gauges (IM8 lm-16, ADR 0060): how many VA Submissions, and how many active Systems with none, are flagged
 * Overdue now. Read from the flag the sweep sets at each scrape, so the gauge is as fresh as the sweep and as the scrape; a
 * count that cannot be read is a gap in the series ({@link KeySignals#overdueGauge}), not a zero. The active Systems are
 * the Register's to say, asked through {@link OverdueSweep} as for the sweep, so a retired System's old flag is not counted.
 */
@Component
class OverdueGauges {

    OverdueGauges(KeySignals signals, SubmissionStore store, OverdueSweep sweep) {
        signals.overdueGauge("submission", store::countOverdueSubmissions);
        signals.overdueGauge("not_started", sweep::countFlaggedNotStarted);
    }
}
