package sg.annualva.cycle;

import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import sg.annualva.web.ResourceNotFoundException;

/**
 * The calendar, read-only, for anyone who holds a role: it names no System and holds no report content, so there is
 * no ownership to check (ADR 0013) and no action to audit (ADR 0014); a person with no role has no use for it.
 */
@RestController
public class CycleController {

    private static final String ANY_ROLE = "hasAnyRole('VA_POC','CYBER_MANAGER','SMO','AUDITOR')";

    private final CycleCalendar calendar;

    CycleController(CycleCalendar calendar) {
        this.calendar = calendar;
    }

    /**
     * @param businessDate today in {@code Asia/Singapore}
     * @param currentCycle the year of the Cycle whose Quarters today falls in, or null before the first live Cycle starts
     * @param cycles the Cycles the app runs, oldest first; while two overlap the dashboards offer a Cycle selector
     */
    public record CycleList(LocalDate businessDate, @Nullable Integer currentCycle, List<CycleView> cycles) {}

    @GetMapping("/api/cycles")
    @PreAuthorize(ANY_ROLE)
    public CycleList cycles() {
        LocalDate today = calendar.today();
        Integer current = calendar.currentCycle().map(VaCycle::year).orElse(null);
        return new CycleList(today, current, calendar.cycles().stream().map(cycle -> CycleView.of(cycle, today)).toList());
    }

    @GetMapping("/api/cycles/{year}")
    @PreAuthorize(ANY_ROLE)
    public CycleView cycle(@PathVariable int year) {
        VaCycle cycle = calendar.cycle(year).orElseThrow(ResourceNotFoundException::new);
        return CycleView.of(cycle, calendar.today());
    }
}
