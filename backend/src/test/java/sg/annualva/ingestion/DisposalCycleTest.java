package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import sg.annualva.boundary.FileStore;
import sg.annualva.harness.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * Story 87 and the spec's retention rule: a VA Cycle's records are kept for the Cycle plus 3 years, then listed, approved
 * by a VA POC and hard-deleted in foreign-key order, the append-only history (proposals, batches, reviews, returns,
 * Declarations, the audit log before the cutoff) included, with a retired System and its Not Started Overdue rows.
 */
class DisposalCycleTest extends DisposalSeam1 {

    /** The start of VA Cycle 2028 (Monday 7 February 2028, Singapore): what was written before it is Cycle 2027's. */
    private static final Instant CUTOFF = Instant.parse("2028-02-06T16:00:00Z");

    private static final List<String> HISTORY = List.of("finding_status_proposal", "bulk_batch", "finding_review",
            "batch_review", "report_review", "submission_return", "submission_return_item", "declaration",
            "notification", "mail_outbox", "not_started_overdue", "cycle_register_system", "register_upload");

    private static final List<String> EMPTIED = List.of("va_submission", "report_file", "report_file_stage", "report_slot",
            "finding", "finding_occurrence", "finding_status_proposal", "bulk_batch", "finding_review", "batch_review",
            "report_review", "submission_return", "submission_return_item", "declaration", "submission_participation",
            "notification", "mail_outbox", "not_started_overdue", "cycle_register", "cycle_register_system",
            "register_upload");

    /** Two Findings read from a file, so a batch, a rescan and a return can be made of them. */
    private static byte[] twoFindings(String alphaPath) {
        return jsonReportOf("{\"name\":\"throwaway-project\",\"reportDate\":\"" + SCANNED + "\"}",
                dependency(PKG_A, alphaPath, "CVE-2027-0001", "9.8"),
                dependency(PKG_B, "/app/bravo.js", "CVE-2027-0002", "7.5"));
    }

    @Test
    void threeYearsAfterItsCycleClosedEveryRecordIsDisposedOfAndOneTombstoneIsWritten() {
        Draft draft = draft();
        String sub = draft.submissionId();
        String f1 = draft.systemId();
        String f2 = idOf("SYS-F2");
        ApiClient smo = draft.smo();
        String first = parsedDc(draft, "first.json", twoFindings("/app/alpha.js"));
        String rescan = replacedAndSettled(draft, first, "rescan.json", twoFindings("/srv/alpha.js")).get("id").asString();
        modeSet(smo, sub, SECURE_CONFIGURATION, mode(MANUAL, "The scanner exports PDF only."));
        entered(smo, sub, manualFinding(SECURE_CONFIGURATION, "Password policy too weak", "SC-PWD-1",
                "password-min-length", "HIGH"));
        modeSet(smo, sub, BURP_SUITE, mode(NOT_APPLICABLE, "The System has no web interface."));
        JsonNode page = findings(smo, sub);
        String alpha = byIdentifier(page, "CVE-2027-0001").get("id").asString();
        String bravo = byIdentifier(page, "CVE-2027-0002").get("id").asString();
        String manual = byIdentifier(page, "SC-PWD-1").get("id").asString();
        String batch = BatchReviewSeam1.bulkSet(draft, List.of(alpha, bravo), remediationPlanned("Upgrade both.", LATER));
        proposeEvery(draft);
        submitted(smo, sub);
        ApiClient cm = signedInAs(CM);
        BatchReviewSeam1.batchReviewed(cm, batch, DISPUTED, "The plan has no owner.");
        reviewed(cm, sub, manual, CONFIRMED, null);
        reportReviewed(cm, sub, BURP_SUITE, CONFIRMED, null);
        returned(cm, sub, "Name an owner for the plan.");
        proposed(smo, alpha, falsePositive("Not reachable in this System."));
        proposed(smo, bravo, falsePositive("Not reachable in this System."));
        submitted(smo, sub);
        reviewed(cm, sub, alpha, CONFIRMED, null);
        reviewed(cm, sub, bravo, CONFIRMED, null);
        passed(cm, sub);
        endorsed(vaPoc(), sub);

        today("2027-08-16");
        arrange.overdueSweep();
        ApiClient poc = vaPoc();
        apply(poc, preview(poc, file(List.of(listed("SYS-F1", "File one", SMO_A, CM, "Q3")))));
        today("2028-02-14");
        lifecycle();
        closed(vaPoc(), 2027);
        JsonNode before = arrange.rows();
        assertThat(HISTORY).as("the test made every kind of history a disposal must remove")
                .allSatisfy(table -> assertThat(before.get(table).asLong()).as(table).isPositive());

        today("2031-02-14");
        retention();
        assertThat(items(listIn(vaPoc(), "DRAFT"))).as("the last day the Cycle is kept: only the superseded file is due")
                .containsExactly("SUPERSEDED_FILE " + first + " SUPERSEDED_AFTER_ENDORSEMENT");

        today("2031-02-15");
        retention();
        JsonNode draftList = listIn(vaPoc(), "DRAFT");
        assertThat(draftList.get("cutoff").asString()).isEqualTo(CUTOFF.toString());
        assertThat(items(draftList)).containsExactlyInAnyOrder(
                "SUBMISSION " + sub + " CYCLE_RETENTION_ENDED",
                "FILE " + first + " CYCLE_RETENTION_ENDED",
                "FILE " + rescan + " CYCLE_RETENTION_ENDED",
                "CYCLE - CYCLE_RETENTION_ENDED",
                "SYSTEM " + f2 + " SYSTEM_RETIRED",
                "AUDIT_RANGE - CYCLE_RETENTION_ENDED");
        JsonNode submissionItem = kindOf(draftList, "SUBMISSION");
        assertThat(submissionItem.get("systemCode").asString()).isEqualTo("SYS-F1");
        assertThat(submissionItem.get("systemName").asString()).isEqualTo("File one");
        assertThat(submissionItem.get("cycle").asInt()).isEqualTo(2027);
        assertThat(kindOf(draftList, "AUDIT_RANGE").get("entries").asLong()).isPositive();
        assertThat(fileStore.keys(FileStore.Zone.CLEAN)).contains(first, rescan);
        Set<String> tombstonesBefore = auditIds("DISPOSAL_EXECUTED");
        approved(vaPoc(), draftList.get("id").asString());

        retention();

        assertThat(listIn(vaPoc(), "EXECUTED").get("id").asString()).isEqualTo(draftList.get("id").asString());
        assertThat(fileStore.keys(FileStore.Zone.CLEAN)).as("the content went first").doesNotContain(first, rescan);
        JsonNode after = arrange.rows();
        assertThat(EMPTIED).allSatisfy(table -> assertThat(after.get(table).asLong()).as(table).isZero());
        assertThat(after.get("va_system").asLong()).as("SYS-F1 is still in the Register").isEqualTo(1);
        ApiClient reader = vaPoc();
        assertThat(read(reader, sub).status()).isEqualTo(404);
        List<String> systems = new ArrayList<>();
        ApiClient.json(reader.get("/api/systems")).forEach(system -> systems.add(system.get("systemId").asString()));
        assertThat(systems).containsExactly("SYS-F1");
        var aboutF1 = reader.get("/api/audit-entries?size=200&system=" + f1);
        ApiClient.json(aboutF1).get("items").forEach(entry -> assertThat(Instant.parse(entry.get("occurredAt").asString()))
                .as("only entries from the cutoff on are kept").isAfterOrEqualTo(CUTOFF));
        List<JsonNode> tombstones = writtenSince("DISPOSAL_EXECUTED", tombstonesBefore);
        assertThat(tombstones).hasSize(1);
        JsonNode detail = tombstones.getFirst().get("detail");
        assertThat(detail.get("disposal_list_id").asString()).isEqualTo(draftList.get("id").asString());
        assertThat(detail.get("submissions").asInt()).isEqualTo(1);
        assertThat(detail.get("files").asInt()).isEqualTo(2);
        assertThat(detail.get("systems").asInt()).isEqualTo(1);
        assertThat(detail.get("superseded_files").asInt()).isZero();
        assertThat(detail.get("exports").asInt()).isZero();
        assertThat(detail.get("audit_entries").asLong()).isPositive();
        assertThat(Instant.parse(tombstones.getFirst().get("occurredAt").asString())).isAfter(CUTOFF);

        retention();
        assertThat(disposalLists(vaPoc())).extracting(list -> list.get("status").asString())
                .as("the Cycle is disposed of once: nothing of it is listed again").doesNotContain("DRAFT");
    }

    /** The one item of this kind on the list. */
    private static JsonNode kindOf(JsonNode list, String kind) {
        List<JsonNode> found = new ArrayList<>();
        list.get("items").forEach(item -> {
            if (item.get("kind").asString().equals(kind)) {
                found.add(item);
            }
        });
        assertThat(found).as("one %s item", kind).hasSize(1);
        return found.getFirst();
    }
}
