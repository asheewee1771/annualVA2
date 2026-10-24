package sg.annualva.cycle;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * One VA Cycle, named after the year it starts.
 *
 * @param year the starting year (VA Cycle 2027 runs February 2027 to January 2028)
 * @param quarters its four Quarters, in order
 * @param closesOn the last day the Cycle is open: Q4's end plus 28 days (a VA POC may close it earlier, in a later ticket)
 * @param firstLiveQuarter the Quarter from which the Cycle runs in this app (1 for a Cycle fully in the app), or null
 *     for a Cycle before the app went live, all of whose Quarters are Handled outside the app
 */
public record VaCycle(int year, List<Quarter> quarters, LocalDate closesOn, @Nullable Integer firstLiveQuarter) {

    public VaCycle {
        quarters = List.copyOf(quarters);
    }

    public LocalDate startsOn() {
        return quarters.getFirst().startsOn();
    }

    /** Q4's last day. */
    public LocalDate endsOn() {
        return quarters.getLast().endsOn();
    }

    public Optional<Quarter> quarterOn(LocalDate day) {
        return quarters.stream().filter(quarter -> quarter.contains(day)).findFirst();
    }

    /**
     * The Quarter a dashboard opens on, on this day: the one the day falls in (even one Handled outside the app); before
     * the Cycle starts its first live Quarter (Q1 for a Cycle the app never ran live); after its last day its Q4.
     */
    public Quarter quarterShownOn(LocalDate day) {
        if (day.isAfter(endsOn())) {
            return quarters.getLast();
        }
        return quarterOn(day).orElseGet(() -> quarters.get(firstLiveQuarter == null ? 0 : firstLiveQuarter - 1));
    }

    /** The Cycle is open through its close date, as a Quarter's endorsement is due through its last day. */
    public CyclePhase phaseOn(LocalDate day) {
        if (day.isBefore(startsOn())) {
            return CyclePhase.UPCOMING;
        }
        if (!day.isAfter(endsOn())) {
            return CyclePhase.RUNNING;
        }
        return day.isAfter(closesOn) ? CyclePhase.CLOSED : CyclePhase.CLOSING;
    }
}
