package sg.annualva.register;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A completed role-holder review (IM8 ac-4, ticket 47): when, by which VA POC (a user id, never a name or an email), how
 * many role holders it listed and which (ADR 0056 item 4, decided 2026-10-26): the ids of the grants the VA POC checked.
 */
public record CompletedReview(Instant reviewedAt, UUID reviewedBy, int holders, List<UUID> grantIds) {

    public CompletedReview {
        grantIds = List.copyOf(grantIds);
    }
}
