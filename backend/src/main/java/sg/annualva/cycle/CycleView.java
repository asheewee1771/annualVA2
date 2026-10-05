package sg.annualva.cycle;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What {@code GET /api/cycles/{year}} answers, and each item of {@code GET /api/cycles}: the Cycle, its Quarters and
 * their windows, and where today falls in it. All dates are business dates in {@code Asia/Singapore}.
 *
 * @param firstLiveQuarter the Quarter from which the Cycle runs in this app; null when every Quarter is Handled outside it
 * @param phase where the Cycle stands today
 * @param current today's Quarter, week and window; null when today is outside the Cycle's four Quarters
 */
public record CycleView(
        int year,
        LocalDate startsOn,
        LocalDate endsOn,
        LocalDate closesOn,
        @Nullable Integer firstLiveQuarter,
        CyclePhase phase,
        List<QuarterView> quarters,
        @Nullable CurrentView current) {

    /** @param endorseDue the Quarter's last day, "week 13" */
    public record QuarterView(
            int number,
            LocalDate startsOn,
            LocalDate endsOn,
            int weeks,
            LocalDate submitDue,
            LocalDate reviewDue,
            LocalDate endorseDue,
            boolean handledOutsideApp,
            List<WindowView> windows) {}

    public record WindowView(Window window, LocalDate startsOn, LocalDate endsOn) {}

    /**
     * @param week the week of the Quarter, from 1 (a long Quarter has a week 14)
     * @param windowEndsOn the last day of the current window
     * @param handledOutsideApp true when the Quarter is before the first live Quarter: no Overdue, no reminders
     */
    public record CurrentView(
            int quarter, int week, Window window, LocalDate windowEndsOn, boolean handledOutsideApp) {}

    static CycleView of(VaCycle cycle, LocalDate today) {
        return new CycleView(cycle.year(), cycle.startsOn(), cycle.endsOn(), cycle.closesOn(), cycle.firstLiveQuarter(),
                cycle.phaseOn(today), cycle.quarters().stream().map(CycleView::of).toList(),
                cycle.quarterOn(today).map(quarter -> current(quarter, today)).orElse(null));
    }

    private static QuarterView of(Quarter quarter) {
        List<WindowView> windows = Arrays.stream(Window.values())
                .map(window -> new WindowView(window, quarter.windowStarts(window), quarter.windowEnds(window)))
                .toList();
        return new QuarterView(quarter.number(), quarter.startsOn(), quarter.endsOn(), quarter.weeks(),
                quarter.submitDue(), quarter.reviewDue(), quarter.endorseDue(), quarter.handledOutsideApp(), windows);
    }

    private static CurrentView current(Quarter quarter, LocalDate today) {
        Window window = quarter.windowOn(today);
        return new CurrentView(quarter.number(), quarter.weekOf(today), window, quarter.windowEnds(window),
                quarter.handledOutsideApp());
    }
}
