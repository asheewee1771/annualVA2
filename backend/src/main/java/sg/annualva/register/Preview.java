package sg.annualva.register;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A Register change preview: what one uploaded file would do. It expires an hour after it was made; nothing in the
 * Register changes until it is applied ({@link RegisterApply}), and a preview with a BLOCKED row cannot be.
 */
public record Preview(UUID id, String status, Instant createdAt, Instant expiresAt, List<PreviewRow> rows) {

    public static final String WAITING = "PREVIEW";

    /**
     * How many rows have each outcome, and how many rows, of whatever outcome, flag an Unlinked SMO. Every row has
     * exactly one outcome, so the five outcome counts add up to the number of rows.
     */
    public record Counts(int add, int update, int retire, int noChange, int blocked, int unlinkedSmo) {}

    public boolean expired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public boolean canApply(Instant now) {
        return WAITING.equals(status) && !expired(now) && count(Outcome.BLOCKED) == 0;
    }

    public Counts counts() {
        return new Counts(count(Outcome.ADD), count(Outcome.UPDATE), count(Outcome.RETIRE), count(Outcome.NO_CHANGE),
                count(Outcome.BLOCKED), (int) rows.stream().filter(PreviewRow::unlinkedSmo).count());
    }

    private int count(Outcome outcome) {
        return (int) rows.stream().filter(row -> row.outcome() == outcome).count();
    }
}
