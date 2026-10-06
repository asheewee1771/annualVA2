package sg.annualva.register;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.RegisterXlsx;
import tools.jackson.databind.JsonNode;

/**
 * Stories 2 and 14: a row that breaks a rule is Blocked, with a fixed reason that names the problem and never repeats
 * the cell; a preview with any Blocked row cannot be applied. Every row is checked again whatever the template's own
 * hints said (ADR 0016).
 */
class RegisterBlockedRowsTest extends RegisterSeam1 {

    private static final String SMO = "smo@ci.annualva.test";
    private static final String MANAGER = "manager@ci.annualva.test";

    @Test
    void aRowWithNoCyberManagerIsBlocked() {
        JsonNode preview = preview(vaPoc(), RegisterXlsx.register().row("SYS-1", "One", SMO, "", "Q1"));

        assertBlocked(preview, "SYS-1", "CYBER_MANAGER_MISSING");
        assertThat(preview.get("canApply").asBoolean()).as("a preview with a Blocked row cannot be applied").isFalse();
        assertThat(preview.get("counts").get("blocked").asInt()).isEqualTo(1);
        assertThat(preview.get("counts").get("add").asInt()).as("a Blocked row is counted as Blocked only").isZero();
    }

    @Test
    void aRowWhoseCyberManagerIsTheSystemsOwnSmoIsBlockedWhateverTheLetterCase() {
        JsonNode preview = preview(vaPoc(), RegisterXlsx.register()
                .row("SYS-1", "One", "Same.Person@ci.annualva.test", "same.person@CI.annualva.test", "Q1"));

        assertBlocked(preview, "SYS-1", "CYBER_MANAGER_IS_SMO");
        assertThat(preview.get("canApply").asBoolean()).isFalse();
    }

    @Test
    void aRowWithAnEmailThatIsNotAnEmailIsBlocked() {
        JsonNode preview = preview(vaPoc(), RegisterXlsx.register()
                .row("SYS-A", "A", "no-at-sign", MANAGER, "Q1")
                .row("SYS-B", "B", SMO, "has space@ci.annualva.test", "Q1")
                .row("SYS-C", "C", "two@@ci.annualva.test", MANAGER, "Q1")
                .row("SYS-D", "D", "nodot@localhost", MANAGER, "Q1")
                .row("SYS-E", "E", ".dot@ci.annualva.test", MANAGER, "Q1")
                .row("SYS-F", "F", "a..b@ci.annualva.test", MANAGER, "Q1")
                .row("SYS-G", "G", "x@-bad-.annualva.test", MANAGER, "Q1")
                .row("SYS-H", "H", "x".repeat(65) + "@ci.annualva.test", MANAGER, "Q1")
                .row("SYS-I", "I", "", MANAGER, "Q1"));

        assertBlocked(preview, "SYS-A", "SMO_EMAIL_INVALID");
        assertBlocked(preview, "SYS-B", "CYBER_MANAGER_EMAIL_INVALID");
        assertBlocked(preview, "SYS-C", "SMO_EMAIL_INVALID");
        assertBlocked(preview, "SYS-D", "SMO_EMAIL_INVALID");
        assertBlocked(preview, "SYS-E", "SMO_EMAIL_INVALID");
        assertBlocked(preview, "SYS-F", "SMO_EMAIL_INVALID");
        assertBlocked(preview, "SYS-G", "SMO_EMAIL_INVALID");
        assertBlocked(preview, "SYS-H", "SMO_EMAIL_INVALID");
        assertBlocked(preview, "SYS-I", "SMO_EMAIL_MISSING");
    }

    @Test
    void aRowWithAnInvalidOrMissingQuarterIsBlockedAndOnlyQ1ToQ4AreAccepted() {
        JsonNode preview = preview(vaPoc(), RegisterXlsx.register()
                .row("SYS-Q1", "One", "q1@ci.annualva.test", MANAGER, "Q1")
                .row("SYS-Q2", "Two", "q2@ci.annualva.test", MANAGER, "Q2")
                .row("SYS-Q3", "Three", "q3@ci.annualva.test", MANAGER, "Q3")
                .row("SYS-Q4", "Four", "q4@ci.annualva.test", MANAGER, "Q4")
                .row("SYS-Q5", "Five", "q5@ci.annualva.test", MANAGER, "Q5")
                .row("SYS-Q0", "Zero", "q0@ci.annualva.test", MANAGER, "Q0")
                .row("SYS-NUM", "Number", "num@ci.annualva.test", MANAGER, "3")
                .row("SYS-LOW", "Lower", "low@ci.annualva.test", MANAGER, "q1")
                .row("SYS-WORD", "Words", "word@ci.annualva.test", MANAGER, "Quarter 1")
                .row("SYS-NONE", "None", "none@ci.annualva.test", MANAGER, ""));

        for (String id : List.of("SYS-Q1", "SYS-Q2", "SYS-Q3", "SYS-Q4")) {
            assertThat(blocked(rowOf(preview, id))).as(id).isFalse();
        }
        for (String id : List.of("SYS-Q5", "SYS-Q0", "SYS-NUM", "SYS-LOW", "SYS-WORD")) {
            assertBlocked(preview, id, "QUARTER_INVALID");
        }
        assertBlocked(preview, "SYS-NONE", "QUARTER_MISSING");
    }

    @Test
    void everyRowThatSharesASystemIdIsBlockedAsADuplicateWithoutRegardToCase() {
        JsonNode preview = preview(vaPoc(), RegisterXlsx.register()
                .row("SYS-DUP", "First", "first@ci.annualva.test", MANAGER, "Q1")
                .row("sys-dup", "Second", "second@ci.annualva.test", MANAGER, "Q2")
                .row("SYS-OK", "Fine", "fine@ci.annualva.test", MANAGER, "Q3"));

        assertBlocked(preview, "SYS-DUP", "SYSTEM_ID_DUPLICATE");
        assertBlocked(preview, "sys-dup", "SYSTEM_ID_DUPLICATE");
        assertThat(blocked(rowOf(preview, "SYS-OK"))).isFalse();
        assertThat(rowOf(preview, "SYS-OK").get("outcome").asString()).isEqualTo("ADD");
        assertThat(preview.get("canApply").asBoolean()).isFalse();
    }

    @Test
    void theTemplatesExampleRowCannotBecomeARealSystem() {
        JsonNode preview = preview(vaPoc(), RegisterXlsx.register()
                .row("EXAMPLE-001", "Example: delete this row before uploading", "smo.example@example.org",
                        "cyber.manager.example@example.org", "Q1")
                .row("example-xyz", "Lower case", "lower@example.org", "lower.cm@example.org", "Q2"));

        assertBlocked(preview, "EXAMPLE-001", "SYSTEM_ID_EXAMPLE");
        assertBlocked(preview, "example-xyz", "SYSTEM_ID_EXAMPLE");
        assertThat(preview.get("canApply").asBoolean()).isFalse();
    }

    @Test
    void aRowWithNoSystemIdOrNoNameOrAnUnusableOneIsBlockedAndCannotBeMatched() {
        JsonNode preview = preview(vaPoc(), RegisterXlsx.register()
                .row("", "No id", "a@ci.annualva.test", MANAGER, "Q1")
                .row("SYS-NONAME", "", "b@ci.annualva.test", MANAGER, "Q1")
                .row("S".repeat(65), "Long id", "c@ci.annualva.test", MANAGER, "Q1")
                .row("SYS-\u200BZW", "Zero width", "d@ci.annualva.test", MANAGER, "Q1")
                .row("SYS-LONGNAME", "N".repeat(201), "e@ci.annualva.test", MANAGER, "Q1"));

        JsonNode noId = preview.get("rows").get(0);
        assertThat(reasons(noId)).containsExactly("SYSTEM_ID_MISSING");
        assertThat(noId.get("existingSystemId").isNull()).as("no usable System ID, so nothing to match").isTrue();
        assertBlocked(preview, "SYS-NONAME", "SYSTEM_NAME_MISSING");
        JsonNode longId = preview.get("rows").get(2);
        assertThat(reasons(longId)).containsExactly("SYSTEM_ID_INVALID");
        assertThat(longId.get("systemId").asString().length()).as("shown clipped").isLessThanOrEqualTo(254);
        assertThat(reasons(preview.get("rows").get(3))).containsExactly("SYSTEM_ID_INVALID");
        assertBlocked(preview, "SYS-LONGNAME", "SYSTEM_NAME_INVALID");
        assertThat(rowOf(preview, "SYS-LONGNAME").get("after").get("name").asString().length()).isLessThanOrEqualTo(254);
    }

    @Test
    void aRowCanBeBlockedForSeveralReasonsAtOnceAndStillShowsWhatTheFileHeld() {
        registered(listed("SYS-MANY", "Before", "before.smo@ci.annualva.test", "before.cm@ci.annualva.test", "Q1"));

        JsonNode preview = preview(vaPoc(), RegisterXlsx.register().row("SYS-MANY", "", "bad", "", "Q9"));

        JsonNode row = rowOf(preview, "SYS-MANY");
        assertThat(reasons(row)).containsExactly("SYSTEM_NAME_MISSING", "SMO_EMAIL_INVALID", "CYBER_MANAGER_MISSING",
                "QUARTER_INVALID");
        assertThat(row.get("outcome").asString()).as("one outcome, whatever else is wrong with the row")
                .isEqualTo("BLOCKED");
        assertThat(row.get("existingSystemId").isNull()).as("it still names the existing System").isFalse();
        assertThat(row.get("before").get("name").asString()).isEqualTo("Before");
        assertThat(row.get("after").get("quarter").asString()).as("what was typed").isEqualTo("Q9");
    }

    @Test
    void aBlockedRowDoesNotRetireTheSystemItNames() {
        registered(listed("SYS-HELD", "Held", "held.smo@ci.annualva.test", "held.cm@ci.annualva.test", "Q1"));

        JsonNode preview = preview(vaPoc(), RegisterXlsx.register().row("SYS-HELD", "Held", "held.smo@ci.annualva.test", "", "Q1"));

        List<String> changes = new ArrayList<>();
        preview.get("rows").forEach(row -> changes.add(row.get("outcome").asString()));
        assertThat(changes).doesNotContain("RETIRE");
    }

    @Test
    void aSpaceThatIsNotAPlainSpaceInASystemIdOrNameIsBlockedSoLookAlikeIdsCannotDiffer() {
        JsonNode preview = preview(vaPoc(), RegisterXlsx.register()
                .row("SYS 1", "Plain space", "plain@ci.annualva.test", MANAGER, "Q1")
                .row("SYS\u20031", "Em space", "em@ci.annualva.test", MANAGER, "Q1")
                .row("SYS-2", "Ideographic\u3000space", "ideo@ci.annualva.test", MANAGER, "Q1"));

        assertThat(blocked(rowOf(preview, "SYS 1"))).isFalse();
        assertThat(preview.get("rows").get(1).get("reasons").get(0).asString()).isEqualTo("SYSTEM_ID_INVALID");
        assertBlocked(preview, "SYS-2", "SYSTEM_NAME_INVALID");
    }

    private static void assertBlocked(JsonNode preview, String systemId, String reason) {
        JsonNode row = rowOf(preview, systemId);
        assertThat(blocked(row)).as(systemId).isTrue();
        assertThat(reasons(row)).as(systemId).contains(reason);
    }

    private static List<String> reasons(JsonNode row) {
        var reasons = new ArrayList<String>();
        row.get("reasons").forEach(reason -> reasons.add(reason.asString()));
        return reasons;
    }
}
