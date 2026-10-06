package sg.annualva.register;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.RegisterXlsx;
import sg.annualva.harness.SignIn;
import tools.jackson.databind.JsonNode;

/**
 * Stories 2, 3 and 4: a VA POC applies a confirmed preview with a fresh sign-in. Systems are added or updated by System
 * ID and the ones the file leaves out are retired, never deleted; a preview is applied once, within the hour, and only
 * against the Register it was made from. All of it happens in one transaction or not at all.
 */
class RegisterApplyTest extends RegisterSeam1 {

    private static final String CM = "apply.cm@ci.annualva.test";

    private static String applyPath(JsonNode preview) {
        return PREVIEWS + preview.get("id").asString() + "/apply";
    }

    private static JsonNode systems(ApiClient poc) {
        var response = poc.get("/api/systems");
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return ApiClient.json(response);
    }

    private static JsonNode system(JsonNode systems, String systemId) {
        for (JsonNode system : systems) {
            if (system.get("systemId").asString().equals(systemId)) {
                return system;
            }
        }
        throw new AssertionError("no System " + systemId + " in " + systems);
    }

    @Test
    void aConfirmedPreviewAddsUpdatesAndRetiresInOneApplyAndNothingIsDeleted() {
        registered(
                List.of(listed("SYS-EDIT", "Old name", "edit.smo@ci.annualva.test", CM, "Q1"),
                        listed("SYS-GONE", "Gone", "gone.smo@ci.annualva.test", CM, "Q3"),
                        listed("SYS-SAME", "Same", "same.smo@ci.annualva.test", CM, "Q2")),
                List.of());
        ApiClient poc = vaPoc();
        JsonNode before = systems(poc);
        String goneId = system(before, "SYS-GONE").get("id").asString();
        JsonNode preview = preview(poc, RegisterXlsx.register()
                .row("SYS-EDIT", "New name", "edit.smo@ci.annualva.test", "other.cm@ci.annualva.test", "Q4")
                .row("sys-same", "Same", "same.smo@ci.annualva.test", CM, "Q2")
                .row("SYS-NEW", "Brand new", "New.SMO@ci.annualva.test", CM, "Q3"));

        var response = applyResponse(poc, preview);

        assertThat(response.status()).as(response.body()).isEqualTo(200);
        assertThat(response.header("cache-control")).contains("no-store");
        JsonNode applied = ApiClient.json(response);
        assertThat(applied.get("previewId").asString()).isEqualTo(preview.get("id").asString());
        assertThat(applied.get("status").asString()).isEqualTo("APPLIED");
        assertThat(applied.get("added").asInt()).isEqualTo(1);
        assertThat(applied.get("updated").asInt()).isEqualTo(1);
        assertThat(applied.get("retired").asInt()).isEqualTo(1);
        assertThat(applied.get("unchanged").asInt()).isEqualTo(1);

        JsonNode after = systems(poc);
        assertThat(after).as("nothing is deleted").hasSize(4);
        JsonNode edit = system(after, "SYS-EDIT");
        assertThat(edit.get("name").asString()).isEqualTo("New name");
        assertThat(edit.get("cyberManagerEmail").asString()).isEqualTo("other.cm@ci.annualva.test");
        assertThat(edit.get("quarter").asString()).isEqualTo("Q4");
        assertThat(edit.get("retired").asBoolean()).isFalse();
        assertThat(system(after, "SYS-SAME").get("retired").asBoolean()).isFalse();
        JsonNode added = system(after, "SYS-NEW");
        assertThat(added.get("smoEmail").asString()).as("emails are kept in lower case").isEqualTo("new.smo@ci.annualva.test");
        assertThat(added.get("quarter").asString()).isEqualTo("Q3");
        JsonNode gone = system(after, "SYS-GONE");
        assertThat(gone.get("retired").asBoolean()).as("a missing System is retired, never deleted").isTrue();
        assertThat(gone.get("id").asString()).as("the same System, so its history stays attached").isEqualTo(goneId);
        assertThat(gone.get("name").asString()).isEqualTo("Gone");
    }

    @Test
    void aRetiredSystemTheFileListsAgainIsReinstatedAsTheSameSystem() {
        registered(
                List.of(listed("SYS-KEEP", "Keep", "keep@ci.annualva.test", CM, "Q1")),
                List.of(listed("SYS-BACK", "Coming back", "back@ci.annualva.test", CM, "Q2")));
        ApiClient poc = vaPoc();
        String id = system(systems(poc), "SYS-BACK").get("id").asString();
        assertThat(system(systems(poc), "SYS-BACK").get("retired").asBoolean()).isTrue();

        apply(poc, preview(poc, file(List.of(
                listed("SYS-KEEP", "Keep", "keep@ci.annualva.test", CM, "Q1"),
                listed("SYS-BACK", "Coming back", "back@ci.annualva.test", CM, "Q2")))));

        JsonNode back = system(systems(poc), "SYS-BACK");
        assertThat(back.get("retired").asBoolean()).isFalse();
        assertThat(back.get("id").asString()).isEqualTo(id);
    }

    @Test
    void aSystemsIdIsMatchedWithoutRegardToCaseSoACaseChangeUpdatesInsteadOfAddingADuplicate() {
        registered(listed("sys-case", "Case", "case@ci.annualva.test", CM, "Q1"));
        ApiClient poc = vaPoc();

        JsonNode preview = preview(poc, RegisterXlsx.register().row("SYS-CASE", "Case", "case@ci.annualva.test", CM, "Q1"));
        apply(poc, preview);

        JsonNode all = systems(poc);
        assertThat(all).hasSize(1);
        assertThat(all.get(0).get("systemId").asString()).as("the System ID as first registered").isEqualTo("sys-case");
    }

    @Test
    void applyingNeedsAFreshSignInAndChangesNothingWithoutOne() {
        registered(listed("SYS-STEP", "Step", "step@ci.annualva.test", CM, "Q1"));
        ApiClient poc = vaPoc();
        JsonNode preview = preview(poc, RegisterXlsx.register().row("SYS-STEP", "Renamed", "step@ci.annualva.test", CM, "Q1"));
        clock.advance(Duration.ofMinutes(6));

        var refused = applyResponse(poc, preview);

        assertThat(refused.status()).isEqualTo(403);
        assertThat(problemType(refused)).isEqualTo("https://annualva/problems/step-up-required");
        assertThat(ApiClient.json(refused).get("stepUpPath").asString()).isEqualTo("/auth/step-up?intent=register-apply");
        assertThat(system(systems(poc), "SYS-STEP").get("name").asString()).as("nothing changed").isEqualTo("Step");

        var flow = SignIn.stepUp(poc, issuer, "/auth/step-up?intent=register-apply&returnTo=/va-poc/register",
                fresh(sg.annualva.harness.TestIdentity.of("af-5001", POC_ONE, "POC One")));
        assertThat(flow.callback().header("location")).endsWith("/va-poc/register?stepup=done");
        poc.withCsrf();

        assertThat(applyResponse(poc, preview).status()).isEqualTo(200);
        assertThat(system(systems(poc), "SYS-STEP").get("name").asString()).isEqualTo("Renamed");
    }

    @Test
    void aPreviewIsAppliedOnceAndTheSecondApplyIsRefusedWithConflict() {
        ApiClient poc = vaPoc();
        JsonNode preview = preview(poc, RegisterXlsx.register().row("SYS-ONCE", "Once", "once@ci.annualva.test", CM, "Q1"));
        apply(poc, preview);

        var again = applyResponse(poc, preview);

        assertThat(again.status()).isEqualTo(409);
        assertThat(problemType(again)).isEqualTo("https://annualva/problems/preview-applied");
        assertThat(systems(poc)).hasSize(1);
        JsonNode read = ApiClient.json(poc.get(PREVIEWS + preview.get("id").asString()));
        assertThat(read.get("status").asString()).isEqualTo("APPLIED");
        assertThat(read.get("canApply").asBoolean()).isFalse();
    }

    @Test
    void anExpiredPreviewCannotBeApplied() {
        ApiClient poc = vaPoc();
        JsonNode preview = preview(poc, RegisterXlsx.register().row("SYS-LATE", "Late", "late@ci.annualva.test", CM, "Q1"));
        clock.advance(Duration.ofHours(1));
        ApiClient later = vaPoc();

        var refused = applyResponse(later, preview);

        assertThat(refused.status()).isEqualTo(409);
        assertThat(problemType(refused)).isEqualTo("https://annualva/problems/preview-expired");
        assertThat(systems(later)).isEmpty();
    }

    @Test
    void aPreviewWithABlockedRowCannotBeApplied() {
        ApiClient poc = vaPoc();
        JsonNode preview = preview(poc, RegisterXlsx.register()
                .row("SYS-FINE", "Fine", "fine@ci.annualva.test", CM, "Q1")
                .row("SYS-BAD", "Bad", "bad@ci.annualva.test", "", "Q1"));

        var refused = applyResponse(poc, preview);

        assertThat(refused.status()).isEqualTo(409);
        assertThat(problemType(refused)).isEqualTo("https://annualva/problems/preview-blocked");
        assertThat(systems(poc)).as("not even the rows that were fine").isEmpty();
    }

    @Test
    void aPreviewThatNeverExistedIsNotFoundAndANonsenseIdIsABadRequest() {
        ApiClient poc = vaPoc();

        var missing = poc.post(PREVIEWS + UUID.randomUUID() + "/apply");
        var nonsense = poc.post(PREVIEWS + "not-an-id/apply");

        assertThat(missing.status()).isEqualTo(404);
        assertThat(nonsense.status()).isEqualTo(400);
    }

    @Test
    void aPreviewMadeBeforeAnotherWasAppliedIsRefusedBecauseTheRegisterHasChanged() {
        registered(listed("SYS-BASE", "Base", "base@ci.annualva.test", CM, "Q1"));
        ApiClient first = vaPoc();
        ApiClient second = otherVaPoc();
        JsonNode firstPreview = preview(first, RegisterXlsx.register()
                .row("SYS-BASE", "Base", "base@ci.annualva.test", CM, "Q1")
                .row("SYS-FIRST", "First", "first@ci.annualva.test", CM, "Q2"));
        JsonNode secondPreview = preview(second, RegisterXlsx.register()
                .row("SYS-BASE", "Renamed by the second", "base@ci.annualva.test", CM, "Q1"));

        assertThat(applyResponse(first, firstPreview).status()).isEqualTo(200);
        var refused = applyResponse(second, secondPreview);

        assertThat(refused.status()).isEqualTo(409);
        assertThat(problemType(refused)).isEqualTo("https://annualva/problems/register-changed");
        JsonNode now = systems(first);
        assertThat(system(now, "SYS-BASE").get("name").asString()).as("the refused apply changed nothing").isEqualTo("Base");
        assertThat(system(now, "SYS-FIRST").get("retired").asBoolean()).isFalse();
    }

    @Test
    void twoPreviewsThatAddTheSameSystemIdCannotBothBeApplied() {
        ApiClient first = vaPoc();
        ApiClient second = otherVaPoc();
        JsonNode firstPreview = preview(first, RegisterXlsx.register().row("SYS-RACE", "First", "first@ci.annualva.test", CM, "Q1"));
        JsonNode secondPreview = preview(second, RegisterXlsx.register().row("sys-race", "Second", "second@ci.annualva.test", CM, "Q2"));

        assertThat(applyResponse(first, firstPreview).status()).isEqualTo(200);
        var refused = applyResponse(second, secondPreview);

        assertThat(refused.status()).isEqualTo(409);
        assertThat(problemType(refused)).isEqualTo("https://annualva/problems/register-changed");
        assertThat(systems(first)).hasSize(1);
        assertThat(systems(first).get(0).get("name").asString()).isEqualTo("First");
    }

    @Test
    void aSystemAddedByAnotherApplySinceThePreviewWasMadeStopsThePreviewToo() {
        ApiClient first = vaPoc();
        ApiClient second = otherVaPoc();
        // Both previews start from an empty Register; the first applies a System the second's file does not list.
        JsonNode firstPreview = preview(first, RegisterXlsx.register().row("SYS-A", "A", "a@ci.annualva.test", CM, "Q1"));
        JsonNode secondPreview = preview(second, RegisterXlsx.register().row("SYS-B", "B", "b@ci.annualva.test", CM, "Q1"));

        assertThat(applyResponse(first, firstPreview).status()).isEqualTo(200);

        assertThat(applyResponse(second, secondPreview).status()).as("it would silently leave SYS-A active").isEqualTo(409);
        assertThat(systems(first)).hasSize(1);
    }

    @Test
    void aLaterApplyOfANewPreviewAfterTheRefusalWorks() {
        ApiClient first = vaPoc();
        ApiClient second = otherVaPoc();
        JsonNode firstPreview = preview(first, RegisterXlsx.register().row("SYS-A", "A", "a@ci.annualva.test", CM, "Q1"));
        JsonNode stale = preview(second, RegisterXlsx.register().row("SYS-A", "Renamed", "a@ci.annualva.test", CM, "Q1"));
        apply(first, firstPreview);
        assertThat(applyResponse(second, stale).status()).isEqualTo(409);

        JsonNode fresh = preview(second, RegisterXlsx.register().row("SYS-A", "Renamed", "a@ci.annualva.test", CM, "Q1"));
        apply(second, fresh);

        assertThat(system(systems(second), "SYS-A").get("name").asString()).isEqualTo("Renamed");
    }
}
