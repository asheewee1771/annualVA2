package sg.annualva.dashboard;

import java.util.UUID;
import sg.annualva.register.RegisterSystems;

/**
 * A System whose Quarter the manual process still runs, as every dashboard lists it apart: named, with no state, dates or
 * action.
 *
 * @param quarter 1 to 4
 */
public record HandledOutside(UUID id, String systemId, String name, int quarter) {

    static HandledOutside of(RegisterSystems.ActiveSystem system) {
        return new HandledOutside(system.id(), system.systemId(), system.name(), system.quarter());
    }
}
