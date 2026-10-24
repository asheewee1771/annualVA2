package sg.annualva.dashboard;

import org.jspecify.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Cycle matrix, read-only, for a VA POC only: an SMO/Tech Lead, a Cyber Manager, an Auditor or a person with no role
 * gets 403. It names no report and no Finding, only states, dates and counts, so reading it is not audited (as for the
 * inboxes, ADR 0019).
 */
@RestController
public class CycleMatrixController {

    private final CycleMatrix matrix;

    CycleMatrixController(CycleMatrix matrix) {
        this.matrix = matrix;
    }

    /** @param cycle the VA Cycle's year; omitted for the Cycle running today */
    @GetMapping("/api/dashboards/cycle-matrix")
    @PreAuthorize("hasRole('VA_POC')")
    public CycleMatrix.Matrix cycleMatrix(@RequestParam(required = false) @Nullable Integer cycle) {
        return matrix.matrix(cycle);
    }
}
