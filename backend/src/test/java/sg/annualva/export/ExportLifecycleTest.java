package sg.annualva.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sg.annualva.boundary.FileStore;
import sg.annualva.harness.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * How an export goes (ticket 44): queued when asked for, made in the background and kept in the file store's export zone
 * for 24 hours, then expired and its file removed; one the store refuses fails. What is asked for is checked first.
 */
class ExportLifecycleTest extends ExportSeam1 {

    @BeforeEach
    void systems() {
        registered(listed("SYS-L1", "Kept", SMO_A, CM, "Q3"), listed("SYS-L2", "Withdrawn", SMO_A, CM, "Q3"),
                listed("SYS-L3", "Outside", SMO_A, CM, "Q1"));
    }

    private static void assertInvalid(ApiClient poc, String body, String field) {
        var response = poc.postJson(EXPORTS, body);
        assertThat(response.status()).as(body + " " + response.body()).isEqualTo(400);
        assertThat(ApiClient.json(response).get("field").asString()).isEqualTo(field);
    }

    @Test
    void anExportIsQueuedUntilTheJobMakesItAndThenKeptForTwentyFourHours() {
        ApiClient poc = vaPoc();
        JsonNode queued = requested(poc, WHOLE_CYCLE);
        String id = queued.get("id").asString();
        assertThat(queued.get("status").asString()).isEqualTo("QUEUED");
        assertThat(queued.get("type").asString()).isEqualTo("MGMT_SUMMARY");
        assertThat(queued.get("format").asString()).isEqualTo("XLSX");
        assertThat(queued.get("cycle").asInt()).isEqualTo(2027);
        assertThat(queued.get("systemCount").asInt()).as("SYS-L1 and SYS-L2; SYS-L3 is handled outside the app")
                .isEqualTo(2);
        assertThat(queued.get("expiresAt").isNull()).isTrue();

        var early = poc.download(EXPORTS + "/" + id + "/download");
        assertThat(early.status()).as("not made yet").isEqualTo(409);
        assertThat(fileStore.count(FileStore.Zone.EXPORT)).isZero();

        exportJob();

        JsonNode ready = export(poc, id);
        assertThat(ready.get("status").asString()).isEqualTo("READY");
        Instant readyAt = Instant.parse(ready.get("readyAt").asString());
        assertThat(Instant.parse(ready.get("expiresAt").asString())).isEqualTo(readyAt.plus(Duration.ofHours(24)));
        assertThat(fileStore.keys(FileStore.Zone.EXPORT)).containsExactly(id);
        exportJob();
        assertThat(export(poc, id).get("readyAt").asString()).as("a second pass changes nothing")
                .isEqualTo(ready.get("readyAt").asString());

        clock.advance(Duration.ofHours(24).minusMinutes(1));
        ApiClient later = vaPoc();
        assertThat(later.download(EXPORTS + "/" + id + "/download").status()).isEqualTo(200);

        clock.advance(Duration.ofMinutes(1));
        ApiClient after = vaPoc();
        assertThat(export(after, id).get("status").asString()).isEqualTo("EXPIRED");
        var gone = after.download(EXPORTS + "/" + id + "/download");
        assertThat(gone.status()).isEqualTo(410);
        exportJob();
        assertThat(fileStore.count(FileStore.Zone.EXPORT)).as("the expired file is removed").isZero();
        assertThat(export(after, id).get("status").asString()).isEqualTo("EXPIRED");
    }

    @Test
    void anExportTheStoreRefusesFailsAndCannotBeDownloaded() {
        ApiClient poc = vaPoc();
        String id = requested(poc, WHOLE_CYCLE).get("id").asString();
        fileStore.refuseWrites(true);

        exportJob();

        assertThat(export(poc, id).get("status").asString()).isEqualTo("FAILED");
        assertThat(poc.download(EXPORTS + "/" + id + "/download").status()).isEqualTo(409);
    }

    @Test
    void anExpiredFileTheStoreCannotRemoveIsNotServedAndIsRemovedOnALaterPass() {
        ApiClient poc = vaPoc();
        String id = requested(poc, WHOLE_CYCLE).get("id").asString();
        exportJob();
        clock.advance(Duration.ofHours(25));
        fileStore.refuseDeletes(true);

        exportJob();

        ApiClient after = vaPoc();
        assertThat(export(after, id).get("status").asString()).isEqualTo("EXPIRED");
        assertThat(after.download(EXPORTS + "/" + id + "/download").status()).isEqualTo(410);
        assertThat(fileStore.count(FileStore.Zone.EXPORT)).isEqualTo(1);
        fileStore.refuseDeletes(false);
        exportJob();
        assertThat(fileStore.count(FileStore.Zone.EXPORT)).isZero();
    }

    @Test
    void whatIsAskedForIsCheckedBeforeAnythingIsQueued() {
        ApiClient poc = vaPoc();
        String sub = started(signedInAs(SMO_A), "SYS-L2");
        arrange.submissionState(sub, "WITHDRAWN");
        String withdrawn = ok(poc, "/api/dashboards/cycle-matrix").get("systems").get(1).get("id").asString();

        assertInvalid(poc, "{\"format\":\"XLSX\"}", "type");
        assertInvalid(poc, "{\"type\":\"DETAILED\",\"format\":\"XLSX\"}", "type");
        assertInvalid(poc, "{\"type\":\"MGMT_SUMMARY\",\"format\":\"CSV\"}", "format");
        assertInvalid(poc, "{\"type\":\"MGMT_SUMMARY\",\"format\":\"XLSX\",\"quarter\":5}", "quarter");
        assertInvalid(poc, "{\"type\":\"MGMT_SUMMARY\",\"format\":\"XLSX\",\"quarter\":1}", "quarter");
        assertInvalid(poc, "{\"type\":\"MGMT_SUMMARY\",\"format\":\"XLSX\",\"systems\":[]}", "systems");
        assertInvalid(poc, "{\"type\":\"MGMT_SUMMARY\",\"format\":\"XLSX\",\"systems\":[\"" + withdrawn + "\"]}",
                "systems");
        assertInvalid(poc, "{\"type\":\"MGMT_SUMMARY\",\"format\":\"XLSX\",\"systems\":"
                + "[\"00000000-0000-0000-0000-000000000000\"]}", "systems");
        var unknownCycle = poc.postJson(EXPORTS, "{\"type\":\"MGMT_SUMMARY\",\"format\":\"XLSX\",\"cycle\":2019}");
        assertThat(unknownCycle.status()).isEqualTo(404);
        assertThat(problemType(unknownCycle)).isEqualTo("https://annualva/problems/not-found");
        assertThat(ok(poc, EXPORTS)).isEmpty();
    }
}
