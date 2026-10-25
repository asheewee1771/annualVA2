package sg.annualva.submission;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import sg.annualva.cycle.CycleCalendar;
import sg.annualva.cycle.CyclePhase;
import sg.annualva.cycle.Quarter;
import sg.annualva.cycle.VaCycle;
import sg.annualva.register.RegisterSystems;

/**
 * Where the jobs that act for nobody look for work by Quarter, written once (ADR 0033 item 3, ADR 0051): every Quarter not
 * Handled outside the app of every VA Cycle the app runs that has not closed on the business date, with the active
 * Systems that Cycle holds in it (an older Cycle keeps its Systems as they were when a newer one opened, ADR 0044). The
 * Overdue sweep flags the Not Started Systems it answers, and the deadline reminders remind the same Systems, so the two
 * never disagree about which work counts. A retired System is never answered. It lists Systems past the access guard,
 * so it is never for a request: the architecture test names its callers.
 */
@Component
class OpenQuarters {

    /** One open Quarter and the ids of the active Systems the Cycle holds in it, in System ID order. */
    record InQuarter(Quarter quarter, List<UUID> systems) {}

    private final RegisterSystems register;
    private final CycleCalendar calendar;

    OpenQuarters(RegisterSystems register, CycleCalendar calendar) {
        this.register = register;
        this.calendar = calendar;
    }

    /** The open Quarters on this business date, oldest Cycle first, by Quarter. */
    List<InQuarter> on(LocalDate today) {
        List<InQuarter> open = new ArrayList<>();
        for (VaCycle cycle : calendar.cyclesOn(today)) {
            if (cycle.phaseOn(today) == CyclePhase.CLOSED) {
                continue;
            }
            List<RegisterSystems.ActiveSystem> members = register.everyActive(cycle.year());
            for (Quarter quarter : cycle.quarters()) {
                if (!quarter.handledOutsideApp()) {
                    open.add(new InQuarter(quarter, members.stream().filter(system -> system.quarter() == quarter.number())
                            .map(RegisterSystems.ActiveSystem::id).toList()));
                }
            }
        }
        return open;
    }
}
