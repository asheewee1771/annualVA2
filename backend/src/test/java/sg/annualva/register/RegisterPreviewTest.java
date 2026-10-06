package sg.annualva.register;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.RegisterXlsx;
import tools.jackson.databind.JsonNode;

/**
 * Stories 2 and 12: a VA POC uploads the filled-in template and sees, before anything changes, every System that
 * would be added, updated or retired, with the values before and after. Nothing in the Register changes.
 */
class RegisterPreviewTest extends RegisterSeam1 {

    @Test
    void aFilledInTemplateGivesAPreviewIdAndEveryNewSystemAsAnAdd() {
        ApiClient poc = vaPoc();
        Instant now = clock.instant();

        var response = upload(poc, RegisterXlsx.register()
                .row("SYS-001", "Payroll", "Alice.Tan@ci.annualva.test", "carol@ci.annualva.test", "Q1")
                .row("SYS-002", "Intranet", "bob@ci.annualva.test", "carol@ci.annualva.test", "Q4")
                .build());

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.header("cache-control")).contains("no-store");
        JsonNode preview = ApiClient.json(response);
        String id = preview.get("id").asString();
        assertThat(UUID.fromString(id)).isNotNull();
        assertThat(response.header("location")).endsWith("/api/system-register/previews/" + id);
        assertThat(preview.get("status").asString()).isEqualTo("PREVIEW");
        assertThat(preview.get("canApply").asBoolean()).isTrue();
        assertThat(Instant.parse(preview.get("createdAt").asString())).isEqualTo(now);
        assertThat(Instant.parse(preview.get("expiresAt").asString())).isEqualTo(now.plus(Duration.ofHours(1)));
        JsonNode counts = preview.get("counts");
        assertThat(counts.get("add").asInt()).isEqualTo(2);
        assertThat(counts.get("update").asInt() + counts.get("retire").asInt() + counts.get("noChange").asInt()
                + counts.get("blocked").asInt()).isZero();

        JsonNode first = rowOf(preview, "SYS-001");
        assertThat(first.get("outcome").asString()).isEqualTo("ADD");
        assertThat(blocked(first)).isFalse();
        assertThat(first.get("reasons")).isEmpty();
        assertThat(first.get("sheetRow").asInt()).isEqualTo(2);
        assertThat(first.get("before").isNull()).isTrue();
        JsonNode after = first.get("after");
        assertThat(after.get("name").asString()).isEqualTo("Payroll");
        assertThat(after.get("smoEmail").asString()).as("emails are kept in lower case").isEqualTo("alice.tan@ci.annualva.test");
        assertThat(after.get("cyberManagerEmail").asString()).isEqualTo("carol@ci.annualva.test");
        assertThat(after.get("quarter").asString()).isEqualTo("Q1");
        assertThat(rowOf(preview, "SYS-002").get("sheetRow").asInt()).isEqualTo(3);
    }

    @Test
    void rowsAreMatchedToTheRegisterBySystemIdAndClassifiedAddUpdateRetireOrNoChange() {
        arrange.registered("SYS-SAME", "Same", "same.smo@ci.annualva.test", "same.cm@ci.annualva.test", 2, false);
        arrange.registered("SYS-EDIT", "Old name", "edit.smo@ci.annualva.test", "edit.cm@ci.annualva.test", 1, false);
        arrange.registered("SYS-GONE", "Gone", "gone.smo@ci.annualva.test", "gone.cm@ci.annualva.test", 3, false);
        arrange.registered("SYS-ALREADY", "Already retired", "old.smo@ci.annualva.test", "old.cm@ci.annualva.test", 4, true);
        arrange.registered("SYS-BACK", "Coming back", "back.smo@ci.annualva.test", "back.cm@ci.annualva.test", 2, true);
        ApiClient poc = vaPoc();

        JsonNode preview = preview(poc, RegisterXlsx.register()
                // the same System, typed in another case and with other letter case in the emails
                .row("sys-same", "Same", "SAME.smo@ci.annualva.test", "same.cm@ci.annualva.test", "Q2")
                .row("SYS-EDIT", "New name", "edit.smo@ci.annualva.test", "new.cm@ci.annualva.test", "Q3")
                .row("SYS-BACK", "Coming back", "back.smo@ci.annualva.test", "back.cm@ci.annualva.test", "Q2")
                .row("SYS-NEW", "Brand new", "new.smo@ci.annualva.test", "new.cm@ci.annualva.test", "Q1"));

        assertThat(rowOf(preview, "sys-same").get("outcome").asString()).isEqualTo("NO_CHANGE");
        JsonNode edit = rowOf(preview, "SYS-EDIT");
        assertThat(edit.get("outcome").asString()).isEqualTo("UPDATE");
        assertThat(edit.get("before").get("name").asString()).isEqualTo("Old name");
        assertThat(edit.get("before").get("cyberManagerEmail").asString()).isEqualTo("edit.cm@ci.annualva.test");
        assertThat(edit.get("before").get("quarter").asString()).isEqualTo("Q1");
        assertThat(edit.get("after").get("name").asString()).isEqualTo("New name");
        assertThat(edit.get("after").get("cyberManagerEmail").asString()).isEqualTo("new.cm@ci.annualva.test");
        assertThat(edit.get("after").get("quarter").asString()).isEqualTo("Q3");
        JsonNode back = rowOf(preview, "SYS-BACK");
        assertThat(back.get("outcome").asString()).as("a retired System the file lists again is reinstated").isEqualTo("UPDATE");
        assertThat(back.get("before").get("retired").asBoolean()).isTrue();
        assertThat(back.get("after").get("retired").asBoolean()).isFalse();
        assertThat(rowOf(preview, "SYS-NEW").get("outcome").asString()).isEqualTo("ADD");

        JsonNode gone = rowOf(preview, "SYS-GONE");
        assertThat(gone.get("outcome").asString()).as("a System the file leaves out is retired, never deleted").isEqualTo("RETIRE");
        assertThat(gone.get("sheetRow").isNull()).isTrue();
        assertThat(gone.get("after").isNull()).isTrue();
        assertThat(gone.get("before").get("name").asString()).isEqualTo("Gone");
        assertThat(ids(preview)).as("a System that is already retired and still left out is not listed again")
                .doesNotContain("SYS-ALREADY");

        JsonNode counts = preview.get("counts");
        assertThat(counts.get("add").asInt()).isEqualTo(1);
        assertThat(counts.get("update").asInt()).isEqualTo(2);
        assertThat(counts.get("retire").asInt()).isEqualTo(1);
        assertThat(counts.get("noChange").asInt()).isEqualTo(1);
        assertThat(counts.get("blocked").asInt()).isZero();
        assertThat(preview.get("canApply").asBoolean()).isTrue();
        assertThat(ids(preview)).as("the file's rows in sheet order, then the Systems it leaves out")
                .containsExactly("sys-same", "SYS-EDIT", "SYS-BACK", "SYS-NEW", "SYS-GONE");
    }

    @Test
    void blankRowsAreSkippedAndEveryRowKeepsItsRowNumberInTheSpreadsheet() {
        JsonNode preview = preview(vaPoc(), RegisterXlsx.register()
                .row("SYS-1", "One", "one@ci.annualva.test", "cm@ci.annualva.test", "Q1")
                .row(null, null, null, null, null)
                .row("SYS-3", "Three", "three@ci.annualva.test", "cm@ci.annualva.test", "Q2"));

        assertThat(preview.get("rows")).hasSize(2);
        assertThat(rowOf(preview, "SYS-1").get("sheetRow").asInt()).isEqualTo(2);
        assertThat(rowOf(preview, "SYS-3").get("sheetRow").asInt()).isEqualTo(4);
    }

    @Test
    void cellsAreTrimmedAndAFormulaIsReadAsItsSavedResultAndNeverEvaluatedByTheServer() {
        JsonNode preview = preview(vaPoc(), RegisterXlsx.register()
                .row("  SYS-9\u00A0", "unused", "  nine@ci.annualva.test ", "cm@ci.annualva.test", " Q4 ")
                .nameByFormula("\"Alpha\"&\"Beta\""));

        JsonNode row = rowOf(preview, "SYS-9");
        assertThat(blocked(row)).isFalse();
        assertThat(row.get("after").get("name").asString()).isEqualTo("AlphaBeta");
        assertThat(row.get("after").get("smoEmail").asString()).isEqualTo("nine@ci.annualva.test");
        assertThat(row.get("after").get("quarter").asString()).isEqualTo("Q4");
    }

    @Test
    void anUploadChangesNothing() {
        arrange.registered("SYS-KEEP", "Keep", "keep.smo@ci.annualva.test", "keep.cm@ci.annualva.test", 1, false);
        ApiClient poc = vaPoc();
        RegisterXlsx file = RegisterXlsx.register()
                .row("SYS-FRESH", "Fresh", "fresh.smo@ci.annualva.test", "fresh.cm@ci.annualva.test", "Q2");

        JsonNode first = preview(poc, file);
        JsonNode second = preview(poc, file);

        assertThat(second.get("id").asString()).as("each upload is a preview of its own").isNotEqualTo(first.get("id").asString());
        assertThat(rowOf(second, "SYS-FRESH").get("outcome").asString()).as("still an add: nothing was applied").isEqualTo("ADD");
        assertThat(rowOf(second, "SYS-KEEP").get("outcome").asString()).as("still there, only to be retired").isEqualTo("RETIRE");
        assertThat(arrange.grantsOf("fresh.smo@ci.annualva.test")).as("no role is granted by a preview").isEmpty();
        assertThat(arrange.grantsOf("fresh.cm@ci.annualva.test")).isEmpty();
    }

    @Test
    void aPreviewCanBeReadBackByAnyVaPocUntilItExpiresAnHourAfterTheUpload() {
        ApiClient poc = vaPoc();
        JsonNode made = preview(poc, RegisterXlsx.register()
                .row("SYS-T", "Timed", "timed@ci.annualva.test", "cm@ci.annualva.test", "Q3"));
        String path = PREVIEWS + made.get("id").asString();

        var read = poc.get(path);
        assertThat(read.status()).isEqualTo(200);
        assertThat(ApiClient.json(read)).isEqualTo(made);
        assertThat(otherVaPoc().get(path).status()).as("either VA POC may read it").isEqualTo(200);

        // each step is shorter than the idle limit, so the session stays alive while the hour runs out
        clock.advance(Duration.ofMinutes(29));
        assertThat(poc.get(path).status()).isEqualTo(200);
        clock.advance(Duration.ofMinutes(29));
        assertThat(poc.get(path).status()).isEqualTo(200);
        clock.advance(Duration.ofMinutes(1).plusSeconds(59));
        assertThat(poc.get(path).status()).as("59 minutes 59 seconds in").isEqualTo(200);
        clock.advance(Duration.ofSeconds(1));
        var expired = poc.get(path);
        assertThat(expired.status()).isEqualTo(410);
        assertThat(problemType(expired)).isEqualTo("https://annualva/problems/preview-expired");
        assertThat(expired.body()).doesNotContain("timed@ci.annualva.test").doesNotContain("Timed");
    }

    @Test
    void aPreviewThatNeverExistedIsNotFoundAndANonsenseIdIsABadRequest() {
        ApiClient poc = vaPoc();

        var missing = poc.get(PREVIEWS + UUID.randomUUID());
        var nonsense = poc.get(PREVIEWS + "not-an-id");

        assertThat(missing.status()).isEqualTo(404);
        assertThat(problemType(missing)).isEqualTo("https://annualva/problems/not-found");
        assertThat(nonsense.status()).isEqualTo(400);
        assertThat(ApiClient.json(nonsense).get("detail").asString()).doesNotContain("not-an-id");
    }

    @Test
    void theRegisterOnlyAcceptsTheUploadAndTheReadOfAPreviewNotAnEditOrADelete() {
        ApiClient poc = vaPoc();
        JsonNode made = preview(poc, RegisterXlsx.register()
                .row("SYS-X", "X", "x@ci.annualva.test", "cm@ci.annualva.test", "Q1"));

        assertThat(poc.postJson(PREVIEWS + made.get("id").asString(), "{}").status()).isIn(404, 405);
        assertThat(poc.get(UPLOAD).status()).isIn(404, 405);
    }

    private static List<String> ids(JsonNode preview) {
        var ids = new ArrayList<String>();
        preview.get("rows").forEach(row -> ids.add(row.get("systemId").asString()));
        return ids;
    }
}
