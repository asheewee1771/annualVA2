package sg.annualva.cycle;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The one-off first live Quarter: the Quarter of a VA Cycle from which that Cycle runs in this app. Earlier Quarters
 * of that Cycle, and every earlier Cycle, are Handled outside the app. The application refuses to start without a valid one.
 *
 * @param firstLiveCycle the VA Cycle that goes live (2027)
 * @param firstLiveQuarter the Quarter of that Cycle it goes live in, 1 to 4 (Q3)
 */
@ConfigurationProperties("annualva.calendar")
@Validated
public record CalendarProperties(
        @Min(CycleCalendar.FIRST_YEAR) @Max(CycleCalendar.LAST_YEAR) int firstLiveCycle,
        @Min(1) @Max(4) int firstLiveQuarter) {}
