package sg.annualva.submission;

import org.springframework.stereotype.Component;
import sg.annualva.access.AccessGuard;
import sg.annualva.access.CurrentUser;
import sg.annualva.access.Need;
import sg.annualva.access.SystemScope;
import sg.annualva.audit.AuditReadScope;
import sg.annualva.audit.AuditReaders;

/**
 * Whose audit history a caller reads (story 85, ADR 0038): a VA POC every entry; a Cyber Manager the entries about the
 * VA Submissions where they acted at the reviewing step ({@code submission_participation}), on Systems the Register still
 * assigns to them. A VA Submission of a System reassigned since, or one they have not reviewed yet, is outside it.
 */
@Component
class AuditHistoryScope implements AuditReaders {

    private final SubmissionStore store;
    private final AccessGuard guard;
    private final CurrentUser user;

    AuditHistoryScope(SubmissionStore store, AccessGuard guard, CurrentUser user) {
        this.store = store;
        this.guard = guard;
        this.user = user;
    }

    @Override
    public AuditReadScope scopeOfCaller() {
        SystemScope systems = guard.scope(Need.AUDIT);
        if (systems.all()) {
            return AuditReadScope.EVERY_ENTRY;
        }
        return AuditReadScope.submissions(store.actedAt(user.id(), SubmissionStep.REVIEW, systems.ids()));
    }
}
