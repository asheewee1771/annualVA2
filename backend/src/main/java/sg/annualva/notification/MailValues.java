package sg.annualva.notification;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import sg.annualva.register.RegisterText;
import sg.annualva.submission.SubmissionState;

/**
 * The values a mail carries and the allow-list they must pass before the relay is called (story 91, ADR 0044): the
 * System's ID and name, the state, the action, the due date, the link and, for a Register change, what changed. Each key
 * has one rule: the System ID and name are the Register's own plain text ({@link RegisterText}), the state, the action and
 * the change are the app's fixed words, the due date is a date and the link is a path of the app. Nothing a person types
 * into a VA Submission and nothing read from a report has a key, so none can reach a mail.
 */
final class MailValues {

    static final String SYSTEM_ID = "system_id";
    static final String SYSTEM_NAME = "system_name";
    static final String STATE = "state";
    static final String ACTION = "action";
    static final String DUE_DATE = "due_date";
    static final String LINK = "link";
    static final String CHANGE_KIND = "change_kind";

    /** What every template accepts. */
    static final Set<String> BASE = Set.of(SYSTEM_ID, SYSTEM_NAME, STATE, ACTION, DUE_DATE, LINK);

    /** What a Register change accepts. */
    static final Set<String> WITH_CHANGE_KIND = Set.of(SYSTEM_ID, SYSTEM_NAME, STATE, ACTION, DUE_DATE, LINK, CHANGE_KIND);

    private static final DateTimeFormatter DUE = DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.ENGLISH);
    private static final Pattern PATH = Pattern.compile("/[a-z0-9/-]{0,200}");
    private static final Set<String> ACTIONS =
            Arrays.stream(NoticeAction.values()).map(NoticeAction::message).collect(Collectors.toUnmodifiableSet());
    private static final Set<String> CHANGES = Arrays.stream(NoticeAction.values()).map(MailValues::changeKind)
            .filter(java.util.Objects::nonNull).collect(Collectors.toUnmodifiableSet());
    private static final Set<String> STATES = states();

    private MailValues() {}

    /**
     * What a notification says, as its template's values. A due date is left out when none applies (a transfer's former
     * SMO/Tech Lead), and the link is the app's address in front of the notification's path.
     */
    static Map<String, String> of(MailOutboxStore.Claimed mail, String appUrl) {
        Map<String, String> values = new HashMap<>();
        values.put(SYSTEM_ID, mail.systemCode());
        values.put(SYSTEM_NAME, mail.systemName());
        values.put(STATE, stateLabel(mail.state()));
        values.put(ACTION, mail.action().message());
        if (mail.dueOn() != null) {
            values.put(DUE_DATE, DUE.format(mail.dueOn()));
        }
        values.put(LINK, appUrl + mail.link());
        String change = changeKind(mail.action());
        if (change != null) {
            values.put(CHANGE_KIND, change);
        }
        return values;
    }

    /** The first key, in key order, that the template does not accept or whose value breaks its rule; empty when none. */
    static Optional<String> outside(MailTemplate template, Map<String, String> values, String appUrl) {
        return values.keySet().stream().sorted()
                .filter(key -> !template.keys().contains(key) || !rule(key, appUrl).test(values.get(key)))
                .findFirst();
    }

    private static Predicate<String> rule(String key, String appUrl) {
        return switch (key) {
            case SYSTEM_ID -> RegisterText::systemId;
            case SYSTEM_NAME -> RegisterText::systemName;
            case STATE -> STATES::contains;
            case ACTION -> ACTIONS::contains;
            case CHANGE_KIND -> CHANGES::contains;
            case DUE_DATE -> MailValues::date;
            case LINK -> value -> value.startsWith(appUrl) && PATH.matcher(value.substring(appUrl.length())).matches();
            default -> value -> false;
        };
    }

    /** What a Register change did, in the app's words; null for any other notification. */
    private static @Nullable String changeKind(NoticeAction action) {
        return switch (action) {
            case SYSTEM_MOVED -> "Moved to another Quarter";
            case SYSTEM_TRANSFERRED_TO_YOU -> "Transferred to you";
            case SYSTEM_TRANSFERRED_FROM_YOU -> "Transferred from you";
            case REVIEW, REWORK, ENDORSE, SUBMIT_OVERDUE, REVIEW_OVERDUE, ENDORSE_OVERDUE -> null;
        };
    }

    /** A state code as the screens name it: {@code RETURNED_FOR_REWORK} is "Returned for Rework". */
    private static String stateLabel(String code) {
        return Arrays.stream(code.split("_"))
                .map(word -> "FOR".equals(word) ? "for" : word.charAt(0) + word.substring(1).toLowerCase(Locale.ROOT))
                .collect(Collectors.joining(" "));
    }

    private static Set<String> states() {
        Set<String> labels = new HashSet<>();
        labels.add(stateLabel(WorkflowNotices.NOT_STARTED));
        Arrays.stream(SubmissionState.values()).forEach(state -> labels.add(stateLabel(state.name())));
        return Set.copyOf(labels);
    }

    private static boolean date(String value) {
        try {
            return DUE.format(LocalDate.parse(value, DUE)).equals(value);
        } catch (DateTimeParseException e) {
            return false;
        }
    }
}
