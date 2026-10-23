package sg.annualva.cycle;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import sg.annualva.config.AnnualVaProperties;
import sg.annualva.web.ResourceNotFoundException;

/**
 * The calendar: every date in it is a business date in {@code Asia/Singapore}, derived from the {@link Clock} bean and
 * never from an instant's UTC date (ADR 0006). A Quarter starts on the first Monday of February, May, August or
 * November and ends the day before the next one starts, so it is 12 to 14 weeks long and "week 13" always means its
 * last day. Q4 ends the day before the next Cycle's Q1.
 */
@Service
public class CycleCalendar {

    /** The years a Cycle can be asked for by year. */
    public static final int FIRST_YEAR = 2000;
    public static final int LAST_YEAR = 2099;

    private static final List<Month> QUARTER_MONTHS = List.of(Month.FEBRUARY, Month.MAY, Month.AUGUST, Month.NOVEMBER);
    private static final int CLOSE_AFTER_Q4_DAYS = 28;

    private final Clock clock;
    private final ZoneId businessZone;
    private final CalendarProperties live;

    CycleCalendar(Clock clock, AnnualVaProperties properties, CalendarProperties live) {
        this.clock = clock;
        this.businessZone = ZoneId.of(properties.timeZone());
        this.live = live;
    }

    /** Today's business date. */
    public LocalDate today() {
        return dateOf(clock.instant());
    }

    /** The business date of an instant. */
    public LocalDate dateOf(Instant instant) {
        return instant.atZone(businessZone).toLocalDate();
    }

    /** The VA Cycle of that year, or nothing for a year outside {@value #FIRST_YEAR} to {@value #LAST_YEAR}. */
    public Optional<VaCycle> cycle(int year) {
        return year < FIRST_YEAR || year > LAST_YEAR ? Optional.empty() : Optional.of(build(year));
    }

    /**
     * The VA Cycles the app runs, oldest first: from the first live Cycle to the one running today. Before the first
     * live Cycle starts, only that Cycle (upcoming).
     */
    public List<VaCycle> cycles() {
        return cyclesOn(today());
    }

    /**
     * The same, on a business date the caller has already read, so a job that reads the clock once sees one day
     * throughout its pass.
     */
    public List<VaCycle> cyclesOn(LocalDate today) {
        int first = live.firstLiveCycle();
        int last = Math.max(first, runningYear(today));
        var cycles = new ArrayList<VaCycle>();
        for (int year = first; year <= last; year++) {
            cycles.add(build(year));
        }
        return cycles;
    }

    /**
     * The VA Cycle asked for by year, or when none is asked for the one running today (before the first live Cycle
     * starts, that Cycle). A year the app does not run is not found.
     */
    public VaCycle running(@Nullable Integer requested) {
        List<VaCycle> running = cycles();
        if (requested == null) {
            return currentCycle().orElse(running.getFirst());
        }
        return running.stream().filter(cycle -> cycle.year() == requested).findFirst().orElseThrow(ResourceNotFoundException::new);
    }

    /** The Cycle whose Quarters today falls in, if the app runs it yet. */
    public Optional<VaCycle> currentCycle() {
        int year = runningYear(today());
        return year < live.firstLiveCycle() ? Optional.empty() : Optional.of(build(year));
    }

    private VaCycle build(int year) {
        var quarters = new ArrayList<Quarter>(QUARTER_MONTHS.size());
        for (int number = 1; number <= QUARTER_MONTHS.size(); number++) {
            LocalDate starts = startOf(year, number);
            LocalDate ends = (number == 4 ? startOf(year + 1, 1) : startOf(year, number + 1)).minusDays(1);
            quarters.add(new Quarter(year, number, starts, ends, handledOutsideApp(year, number)));
        }
        LocalDate closesOn = quarters.getLast().endsOn().plusDays(CLOSE_AFTER_Q4_DAYS);
        return new VaCycle(year, quarters, closesOn, firstLiveQuarter(year));
    }

    /** The first Monday of the Quarter's month. */
    private static LocalDate startOf(int year, int quarter) {
        Month month = QUARTER_MONTHS.get(quarter - 1);
        return LocalDate.of(year, month, 1).with(TemporalAdjusters.firstInMonth(DayOfWeek.MONDAY));
    }

    /** The year of the Cycle whose Quarters today falls in: this year from its Q1, else last year. */
    private static int runningYear(LocalDate today) {
        return today.isBefore(startOf(today.getYear(), 1)) ? today.getYear() - 1 : today.getYear();
    }

    private boolean handledOutsideApp(int year, int quarter) {
        return year < live.firstLiveCycle() || (year == live.firstLiveCycle() && quarter < live.firstLiveQuarter());
    }

    private @Nullable Integer firstLiveQuarter(int year) {
        if (year < live.firstLiveCycle()) {
            return null;
        }
        return year == live.firstLiveCycle() ? live.firstLiveQuarter() : 1;
    }
}
