package sg.annualva.register;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.RegisterXlsx;
import tools.jackson.databind.JsonNode;

/**
 * IM8 lm-4 and lm-19 for the upload: a preview, a read of it and a refused file are each one audit entry that carries
 * the person, their role and fixed codes or ids, and no file content. What the file held (names, emails, System IDs,
 * free text) reaches only the preview the VA POC asked for, never an error body, an audit entry or the log.
 */
@ExtendWith(OutputCaptureExtension.class)
class RegisterUploadAuditTest extends RegisterSeam1 {

    private static final String SENTINEL = "CVE-9999-SENTINEL";
    private static final Set<String> KEYS = Set.of("preview_id", "count", "reject_code");

    @Test
    void aPreviewIsAuditedWithItsIdAndTheNumberOfRowsAndNothingFromTheFile() {
        ApiClient poc = vaPoc();
        String actor = userId(poc);

        JsonNode preview = preview(poc, RegisterXlsx.register()
                .row("SYS-AUD-1", "Audited " + SENTINEL, "audit.one@ci.annualva.test", "audit.cm@ci.annualva.test", "Q1")
                .row("SYS-AUD-2", "Two", "audit.two@ci.annualva.test", "audit.cm@ci.annualva.test", "Q2"));

        JsonNode entries = entries(poc, "REGISTER_PREVIEWED", actor);
        assertThat(entries.get("total").asLong()).isGreaterThanOrEqualTo(1);
        JsonNode entry = null;
        for (JsonNode candidate : entries.get("items")) {
            if (candidate.get("detail").get("preview_id").asString().equals(preview.get("id").asString())) {
                entry = candidate;
            }
        }
        assertThat(entry).as("an entry for this preview").isNotNull();
        assertThat(entry.get("outcome").asString()).isEqualTo("SUCCESS");
        assertThat(entry.get("actorId").asString()).isEqualTo(actor);
        assertThat(entry.get("actorRole").asString()).isEqualTo("VA_POC");
        assertThat(entry.get("systemId").isNull()).as("a preview is about no one System").isTrue();
        assertThat(entry.get("detail").get("preview_id").asString()).isEqualTo(preview.get("id").asString());
        assertThat(entry.get("detail").get("count").asInt()).isEqualTo(2);
        assertThat(entry.get("detail").size()).isEqualTo(2);
        assertNoContent(entry);
    }

    @Test
    void readingAPreviewBackIsAuditedToo() {
        ApiClient poc = vaPoc();
        String actor = userId(poc);
        JsonNode preview = preview(poc, RegisterXlsx.register()
                .row("SYS-AUD-3", "Three", "audit.three@ci.annualva.test", "audit.cm@ci.annualva.test", "Q3"));
        long before = entries(poc, "REGISTER_PREVIEW_VIEWED", actor).get("total").asLong();

        assertThat(poc.get(PREVIEWS + preview.get("id").asString()).status()).isEqualTo(200);

        JsonNode entries = entries(poc, "REGISTER_PREVIEW_VIEWED", actor);
        assertThat(entries.get("total").asLong()).isEqualTo(before + 1);
        // other tests read previews at other (moved) clock times, so the entry is found by its preview rather than by position
        List<String> previews = new java.util.ArrayList<>();
        entries.get("items").forEach(entry -> previews.add(entry.get("detail").get("preview_id").asString()));
        assertThat(previews).contains(preview.get("id").asString());
    }

    @Test
    void aRefusedFileIsAuditedWithAFixedCodeAndNothingElse() {
        ApiClient poc = vaPoc();
        String actor = userId(poc);
        long before = entries(poc, "REGISTER_UPLOAD_REJECTED", actor).get("total").asLong();

        upload(poc, "not a workbook".getBytes(StandardCharsets.UTF_8));
        upload(poc, RegisterXlsx.register().headers("System ID", SENTINEL, "x", "y", "z").row("a", "b", "c", "d", "e").build());
        upload(poc, RegisterXlsx.register().build());
        upload(poc, new byte[0]);

        JsonNode entries = entries(poc, "REGISTER_UPLOAD_REJECTED", actor);
        assertThat(entries.get("total").asLong()).isEqualTo(before + 4);
        List<String> codes = List.of(
                entries.get("items").get(0).get("detail").get("reject_code").asString(),
                entries.get("items").get(1).get("detail").get("reject_code").asString(),
                entries.get("items").get(2).get("detail").get("reject_code").asString(),
                entries.get("items").get(3).get("detail").get("reject_code").asString());
        assertThat(codes).containsExactly("empty", "no-rows", "header-mismatch", "not-xlsx");
        for (int i = 0; i < 4; i++) {
            JsonNode entry = entries.get("items").get(i);
            assertThat(entry.get("outcome").asString()).isEqualTo("REFUSED");
            assertThat(entry.get("actorRole").asString()).isEqualTo("VA_POC");
            assertThat(entry.get("detail").size()).isEqualTo(1);
            assertNoContent(entry);
        }
    }

    @Test
    void aRefusedFileIsNeverQuotedInTheErrorTheAuditLogOrTheApplicationLog(CapturedOutput output) {
        ApiClient poc = vaPoc();
        String actor = userId(poc);
        String named = SENTINEL + ".xlsx";
        byte[][] files = {
            RegisterXlsx.register().headers("System ID", SENTINEL, "SMO/Tech Lead email", "Cyber Manager email", "Quarter")
                    .row(SENTINEL, SENTINEL, SENTINEL + "@ci.annualva.test", SENTINEL, SENTINEL).build(),
            ("<html>" + SENTINEL + "</html>").getBytes(StandardCharsets.UTF_8),
            RegisterXlsx.workbookWithSheets(SENTINEL, "Other"),
        };

        for (byte[] file : files) {
            var response = poc.postFile(UPLOAD, "file", named, "text/" + SENTINEL.toLowerCase(), file);

            assertThat(response.status()).isGreaterThanOrEqualTo(400);
            assertThat(response.body().toLowerCase()).doesNotContain("sentinel").doesNotContain("cve-9999");
        }
        // a file that is accepted: its content is in the preview the person asked for, and nowhere else
        var accepted = poc.postFile(UPLOAD, "file", named, XLSX, RegisterXlsx.register()
                .row("SYS-" + SENTINEL, "Name " + SENTINEL, "smo-" + SENTINEL.toLowerCase() + "@ci.annualva.test",
                        "cm@ci.annualva.test", "Q1").build());
        assertThat(accepted.status()).isEqualTo(201);
        poc.get(PREVIEWS + ApiClient.json(accepted).get("id").asString());

        for (String action : List.of("REGISTER_UPLOAD_REJECTED", "REGISTER_PREVIEWED", "REGISTER_PREVIEW_VIEWED")) {
            for (JsonNode entry : entries(poc, action, actor).get("items")) {
                assertNoContent(entry);
            }
        }
        assertThat(output.getAll().toLowerCase()).doesNotContain("sentinel").doesNotContain("cve-9999");
    }

    private static void assertNoContent(JsonNode entry) {
        String text = entry.toString().toLowerCase();
        assertThat(text).doesNotContain("sentinel").doesNotContain("cve-9999").doesNotContain("sys-aud")
                .doesNotContain("audit.").doesNotContain("@ci.annualva.test");
        entry.get("detail").propertyNames().forEach(key -> assertThat(KEYS).contains(key));
    }

    private static String userId(ApiClient browser) {
        return ApiClient.json(browser.get("/api/session")).get("uuid").asString();
    }

    private JsonNode entries(ApiClient reader, String action, String actor) {
        var response = reader.get("/api/audit-entries?size=200&action=" + action + "&actor=" + actor + untilToday());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return ApiClient.json(response);
    }
}
