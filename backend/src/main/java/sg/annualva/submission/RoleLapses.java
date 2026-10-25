package sg.annualva.submission;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import sg.annualva.access.ReturnTrigger;
import sg.annualva.access.Role;
import sg.annualva.access.RoleLapse;
import sg.annualva.cycle.CycleCalendar;

/**
 * What the VA Submissions tell the role lapse (ticket 46, ADR 0055): when each System's Quarter began, for the daily pass,
 * and when work on a System is handed to a person whose role has lapsed. A Return for Rework hands the VA Submission to
 * its SMO/Tech Lead and a submit to its Cyber Manager; a conditional Exemption that lapses back with new windows hands it
 * to its SMO/Tech Lead and, back In Review, to its Cyber Manager too (a lapse to Not Endorsed has no new windows, and one
 * Awaiting Endorsement waits on the VA POCs, whose role only configuration returns). The returns are heard inside the
 * command's or the lapse job's transaction, so they commit or roll back with the move. The scheduler
 * ({@code RoleLapseJob}) or a Seam 1 test runs the pass.
 */
@Service
public class RoleLapses {

    private final RoleLapse lapse;
    private final OpenQuarters openQuarters;
    private final CycleCalendar calendar;
    private final Clock clock;

    RoleLapses(RoleLapse lapse, OpenQuarters openQuarters, CycleCalendar calendar, Clock clock) {
        this.lapse = lapse;
        this.openQuarters = openQuarters;
        this.calendar = calendar;
        this.clock = clock;
    }

    /**
     * One pass: for each active System, when the latest of its open Quarters ({@link OpenQuarters}, the one rule the
     * Overdue sweep and the reminders read) that has begun began; then the role lapse returns and lapses by it. A Quarter
     * of a closed Cycle is left out, but by then it began more than 90 days ago, so it would change nothing. The clock is
     * read once per pass.
     */
    @Transactional
    public void run() {
        Instant now = clock.instant();
        LocalDate today = calendar.dateOf(now);
        Map<UUID, Instant> began = new HashMap<>();
        for (OpenQuarters.InQuarter open : openQuarters.on(today)) {
            if (!open.quarter().startsOn().isAfter(today)) {
                Instant start = calendar.startOf(open.quarter().startsOn());
                open.systems().forEach(system -> began.merge(system, start,
                        (one, other) -> one.isAfter(other) ? one : other));
            }
        }
        lapse.pass(began, now);
    }

    /**
     * A move hands the VA Submission to whoever is assigned to the step its new state waits on ({@link
     * SubmissionState#nextStep}): a return for rework to its SMO/Tech Lead, a submit to its Cyber Manager. Other moves are
     * made by the person who acts next, or hand it to the VA POCs. A rejected Exemption that puts a VA Submission back where
     * the request froze it hands nothing over: the SMO/Tech Lead asked for it just before.
     */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    void on(SubmissionEvent event) {
        if (event.from() == SubmissionState.EXEMPTION_REQUESTED) {
            return;
        }
        switch (event.to()) {
            case RETURNED_FOR_REWORK -> handedTo(event.to(), event.systemId(), ReturnTrigger.RETURN_FOR_REWORK);
            case IN_REVIEW -> handedTo(event.to(), event.systemId(), ReturnTrigger.SUBMITTED_FOR_REVIEW);
            default -> { }
        }
    }

    /**
     * A conditional Exemption that lapses back with new windows hands the VA Submission to its SMO/Tech Lead, who is told
     * and must act in the new Submission Window, and to whoever the restored state waits on. One that becomes Not Endorsed
     * has no new windows and waits on nobody.
     */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    void on(ExemptionEvent event) {
        if (event.kind() != ExemptionEvent.Kind.LAPSED || event.state().nextStep().isEmpty()) {
            return;
        }
        lapse.handBack(Role.SMO, event.systemId(), ReturnTrigger.EXEMPTION_LAPSE);
        handedTo(event.state(), event.systemId(), ReturnTrigger.EXEMPTION_LAPSE);
    }

    private void handedTo(SubmissionState state, UUID systemId, ReturnTrigger trigger) {
        state.nextStep().flatMap(SubmissionStep::assignedRole)
                .ifPresent(role -> lapse.handBack(role, systemId, trigger));
    }
}
