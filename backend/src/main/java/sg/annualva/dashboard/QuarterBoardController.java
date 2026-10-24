package sg.annualva.dashboard;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.jspecify.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Quarter board, read-only, for a Cyber Manager (the Systems assigned to them and nobody else's) and a VA POC (every
 * System). It names no report and no Finding, only counts, so reading it is not audited (as for the inboxes, ADR 0019).
 */
@RestController
public class QuarterBoardController {

    private final QuarterBoard board;

    QuarterBoardController(QuarterBoard board) {
        this.board = board;
    }

    /**
     * @param cycle the VA Cycle's year; omitted for the Cycle running today
     * @param quarter 1 to 4; omitted for the Quarter running today
     */
    @GetMapping("/api/dashboards/quarter-board")
    @PreAuthorize("hasAnyRole('CYBER_MANAGER', 'VA_POC')")
    public QuarterBoard.Board quarterBoard(@RequestParam(required = false) @Nullable Integer cycle,
            @RequestParam(required = false) @Nullable @Min(1) @Max(4) Integer quarter) {
        return board.board(cycle, quarter);
    }
}
