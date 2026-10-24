package sg.annualva.notification;

import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import sg.annualva.register.RegisterText;
import sg.annualva.submission.SubmissionState;

/**
 * The values a mail carries, and the allow-list they pass before the relay is called (story 91, ADR 0044): the System's
 * ID and name, the state, the action, the due date, the link and, for a Register change, what changed. There is no other
 * key, so nothing a person types into a VA Submission and nothing read from a report can reach a mail. The action, the
 * change and the due date are built from typed fields in the app's own words; the values read from the database as text
 * each have a rule: the System ID and name are the Register's plain text ({@link RegisterText}), the state is one the app
 * names, and the link is a path of the app.
 */
final class MailValues {

    static final String SYSTEM_ID = "system_id";
    static final String SYSTEM_NAME = "system_name";
    static final String STATE = "state";
    static final String ACTION = "action";
    static final String DUE_DATE = "due_date";
    static final String LINK = "link";
    static final String CHANGE_KIND = "change_kind";

    private static final DateTimeFormatter DUE = DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.ENGLISH);
    private static final Pattern PATH = Pattern.compile("/[a-z0-9/-]{0,200}");
    private static final String NOT_STARTED_LABEL = "Not Started";

    private MailValues() {}

    /** The key of the first value that breaks its rule; empty when every value passes. */
    static Optional<String> refusal(MailOutboxStore.Claimed mail) {
        if (!RegisterText.systemId(mail.systemCode())) {
            return Optional.of(SYSTEM_ID);
        }
        if (!RegisterText.systemName(mail.systemName())) {
            return Optional.of(SYSTEM_NAME);
        }
        if (stateLabel(mail.state()).isEmpty()) {
            return Optional.of(STATE);
        }
        if (!PATH.matcher(mail.link()).matches()) {
            return Optional.of(LINK);
        }
        return Optional.empty();
    }

    /**
     * What a notification says, as its template's values, once {@link #refusal} found nothing. A due date is left out
     * when none applies (a transfer's former SMO/Tech Lead), and the link is the app's address in front of the path.
     */
    static Map<String, String> of(MailOutboxStore.Claimed mail, String appUrl) {
        Map<String, String> values = new HashMap<>();
        values.put(SYSTEM_ID, mail.systemCode());
        values.put(SYSTEM_NAME, mail.systemName());
        values.put(STATE, stateLabel(mail.state()).orElseThrow());
        values.put(ACTION, mail.action().message());
        if (mail.dueOn() != null) {
            values.put(DUE_DATE, DUE.format(mail.dueOn()));
        }
        values.put(LINK, appUrl + mail.link());
        MailTemplate.of(mail.action()).map(MailTemplate.Mailing::changeKind)
                .ifPresent(change -> values.put(CHANGE_KIND, change));
        return values;
    }

    /** A state code as the screens name it; empty for a code the app does not know. */
    private static Optional<String> stateLabel(String code) {
        if (WorkflowNotices.NOT_STARTED.equals(code)) {
            return Optional.of(NOT_STARTED_LABEL);
        }
        return Arrays.stream(SubmissionState.values()).filter(state -> state.name().equals(code)).findFirst()
                .map(SubmissionState::label);
    }
}
