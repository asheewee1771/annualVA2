package sg.annualva.cycle;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * One Quarter of a VA Cycle: it starts on a Monday and runs to the day before the next Quarter starts (12 to 14 weeks).
 *
 * @param cycle the year of the VA Cycle it belongs to
 * @param number 1 to 4
 * @param startsOn the first Monday of February, May, August or November
 * @param endsOn its last day, which is also the day endorsement is due ("week 13")
 * @param handledOutsideApp true for a Quarter before the first live Quarter: left out of counts, reminders and the close
 */
public record Quarter(int cycle, int number, LocalDate startsOn, LocalDate endsOn, boolean handledOutsideApp) {

    /** Two weeks after the start: the last day of the Submission Window. */
    public LocalDate submitDue() {
        return startsOn.plusDays(13);
    }

    /** Four weeks after the start: the last day of the Review Window. */
    public LocalDate reviewDue() {
        return startsOn.plusDays(27);
    }

    public LocalDate endorseDue() {
        return endsOn;
    }

    public int weeks() {
        return (int) (ChronoUnit.DAYS.between(startsOn, endsOn) + 1) / 7;
    }

    public boolean contains(LocalDate day) {
        return !day.isBefore(startsOn) && !day.isAfter(endsOn);
    }

    /** The week of the Quarter the day falls in, from 1; the day must be inside the Quarter. */
    public int weekOf(LocalDate day) {
        return (int) ChronoUnit.DAYS.between(startsOn, day) / 7 + 1;
    }

    /** The window the day falls in; the day must be inside the Quarter. */
    public Window windowOn(LocalDate day) {
        if (!day.isAfter(submitDue())) {
            return Window.SUBMISSION;
        }
        return day.isAfter(reviewDue()) ? Window.ENDORSEMENT : Window.REVIEW;
    }

    /** The first day of the given window. */
    public LocalDate windowStarts(Window window) {
        return switch (window) {
            case SUBMISSION -> startsOn;
            case REVIEW -> submitDue().plusDays(1);
            case ENDORSEMENT -> reviewDue().plusDays(1);
        };
    }

    /** The last day of the given window. */
    public LocalDate windowEnds(Window window) {
        return switch (window) {
            case SUBMISSION -> submitDue();
            case REVIEW -> reviewDue();
            case ENDORSEMENT -> endorseDue();
        };
    }
}
