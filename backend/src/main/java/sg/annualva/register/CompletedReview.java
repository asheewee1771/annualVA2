package sg.annualva.register;

import java.time.Instant;
import java.util.UUID;

/**
 * A completed role-holder review (IM8 ac-4, ticket 47): when, by which VA POC (a user id, never a name or an email) and
 * how many role holders it listed.
 */
public record CompletedReview(Instant reviewedAt, UUID reviewedBy, int holders) {}
