package sg.annualva.submission;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The fixed Declaration wording the SMO/Tech Lead accepts when submitting (stories 40 and 41). People outside the app own
 * the wording (ticket 24): each wording is a version, added by a migration and never changed, and the current one is the
 * highest. Until the agreed wording is added, version 1 is a placeholder and says so.
 */
@Service
public class Declarations {

    /**
     * One version of the wording.
     *
     * @param placeholder true while the wording is not the agreed one: the screen marks it as a placeholder
     */
    public record Text(int version, String text, boolean placeholder) {}

    private final SubmissionStore store;

    Declarations(SubmissionStore store) {
        this.store = store;
    }

    /** The wording the SMO/Tech Lead is asked to accept now. It is no one's data, so the read is not audited. */
    @Transactional(readOnly = true)
    public Text current() {
        return store.currentDeclarationText();
    }
}
