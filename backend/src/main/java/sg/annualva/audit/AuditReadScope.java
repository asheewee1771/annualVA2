package sg.annualva.audit;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Which entries a caller may read (the permissions matrix, story 85): every entry (a VA POC), or exactly the entries
 * about these VA Submissions (a Cyber Manager: those they reviewed on Systems still assigned to them).
 */
public record AuditReadScope(boolean everyEntry, Set<UUID> submissions) {

    public static final AuditReadScope EVERY_ENTRY = new AuditReadScope(true, Set.of());

    public AuditReadScope {
        submissions = Set.copyOf(submissions);
    }

    /** Exactly the entries about these VA Submissions. */
    public static AuditReadScope submissions(Collection<UUID> submissionIds) {
        return new AuditReadScope(false, Set.copyOf(submissionIds));
    }

    boolean allows(UUID submissionId) {
        return everyEntry || submissions.contains(submissionId);
    }

    /** The VA Submissions a read is narrowed to, or null when it is not narrowed. */
    @Nullable Set<UUID> narrowing() {
        return everyEntry ? null : submissions;
    }
}
