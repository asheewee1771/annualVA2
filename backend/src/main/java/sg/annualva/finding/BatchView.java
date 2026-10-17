package sg.annualva.finding;

import java.util.UUID;

/**
 * A Bulk-set batch as its step answers it (story 37).
 *
 * @param batchId the batch every proposal of the step names; the Cyber Manager reviews the batch by it (ticket 29)
 * @param count how many Findings it was applied to
 */
public record BatchView(UUID batchId, int count) {}
