package sg.annualva.register;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.RegisterXlsx;
import tools.jackson.databind.JsonNode;

/** Story 12: a System whose SMO/Tech Lead's email matches no sign-in yet is flagged Unlinked SMO in the preview. */
class RegisterUnlinkedSmoTest extends RegisterSeam1 {

    private static final String MANAGER = "unlinked.cm@ci.annualva.test";

    @Test
    void anSmoWhoHasSignedInIsLinkedAndAnyoneElseIsFlaggedUnlinked() {
        String signedIn = "linked.smo@ci.annualva.test";
        signedInAs(signedIn);
        String preRegistered = "waiting.smo@ci.annualva.test";
        arrange.preRegister(preRegistered);
        ApiClient poc = vaPoc();

        JsonNode preview = preview(poc, RegisterXlsx.register()
                .row("SYS-L", "Linked", signedIn, MANAGER, "Q1")
                .row("SYS-U", "Unknown", "never.seen@ci.annualva.test", MANAGER, "Q1")
                .row("SYS-P", "Pre-registered", preRegistered, MANAGER, "Q1"));

        assertThat(rowOf(preview, "SYS-L").get("unlinkedSmo").asBoolean()).isFalse();
        assertThat(rowOf(preview, "SYS-U").get("unlinkedSmo").asBoolean()).isTrue();
        assertThat(rowOf(preview, "SYS-P").get("unlinkedSmo").asBoolean()).as("known by email, not yet signed in").isTrue();
        assertThat(preview.get("counts").get("unlinkedSmo").asInt()).isEqualTo(2);
        assertThat(preview.get("canApply").asBoolean()).as("an Unlinked SMO flags, it does not block").isTrue();
    }

    @Test
    void theEmailIsMatchedWithoutRegardToLetterCaseAndTheFlagClearsOnceThePersonSignsIn() {
        ApiClient poc = vaPoc();
        String email = "late.joiner@ci.annualva.test";
        RegisterXlsx file = RegisterXlsx.register().row("SYS-J", "Joiner", email.toUpperCase(), MANAGER, "Q2");

        assertThat(rowOf(preview(poc, file), "SYS-J").get("unlinkedSmo").asBoolean()).isTrue();

        signedInAs(email);

        assertThat(rowOf(preview(poc, file), "SYS-J").get("unlinkedSmo").asBoolean()).isFalse();
    }

    @Test
    void anUnchangedSystemWithAnUnlinkedSmoIsStillListedAndFlagged() {
        arrange.registered("SYS-SAME", "Same", "same.unlinked@ci.annualva.test", MANAGER, 2, false);

        JsonNode preview = preview(vaPoc(), RegisterXlsx.register()
                .row("SYS-SAME", "Same", "same.unlinked@ci.annualva.test", MANAGER, "Q2"));

        JsonNode row = rowOf(preview, "SYS-SAME");
        assertThat(row.get("outcome").asString()).isEqualTo("NO_CHANGE");
        assertThat(row.get("unlinkedSmo").asBoolean()).as("so the summary never counts a flag nobody can see").isTrue();
        assertThat(preview.get("counts").get("unlinkedSmo").asInt()).isEqualTo(1);
    }

    @Test
    void theFlagIsKeptWithThePreviewWhenItIsReadBack() {
        ApiClient poc = vaPoc();
        JsonNode made = preview(poc, RegisterXlsx.register()
                .row("SYS-K", "Kept", "kept.smo@ci.annualva.test", MANAGER, "Q3"));

        JsonNode read = ApiClient.json(poc.get(PREVIEWS + made.get("id").asString()));

        assertThat(rowOf(read, "SYS-K").get("unlinkedSmo").asBoolean()).isTrue();
        assertThat(read.get("counts").get("unlinkedSmo").asInt()).isEqualTo(1);
    }
}
