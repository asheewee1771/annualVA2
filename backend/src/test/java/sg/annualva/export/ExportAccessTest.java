package sg.annualva.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.SignIn;
import sg.annualva.harness.TestIdentity;
import tools.jackson.databind.JsonNode;

/**
 * Who may export (ticket 44, IM8 ac-1 and ac-2, story 84): asking for the management summary is the VA POCs' alone and
 * needs a fresh sign-in; an export is read and downloaded by its requester alone; asking and downloading are audited with
 * ids and fixed codes only.
 */
class ExportAccessTest extends ExportSeam1 {

    @BeforeEach
    void oneSystem() {
        registered(listed("SYS-A1", "Access", SMO_A, CM, "Q3"));
    }

    @Test
    void askingNeedsAFreshSignInAndQueuesNothingWithoutOne() {
        ApiClient poc = vaPoc();
        int askedBefore = entries("EXPORT_REQUESTED", userId(poc)).size();
        clock.advance(Duration.ofMinutes(6));

        var refused = poc.postJson(EXPORTS, WHOLE_CYCLE);

        assertThat(refused.status()).isEqualTo(403);
        assertThat(ApiClient.json(refused).get("type").asString())
                .isEqualTo("https://annualva/problems/step-up-required");
        assertThat(ApiClient.json(refused).get("stepUpPath").asString())
                .isEqualTo("/auth/step-up?intent=export-mgmt-summary");
        assertThat(ok(poc, EXPORTS)).isEmpty();
        assertThat(entries("EXPORT_REQUESTED", userId(poc))).hasSize(askedBefore);

        var flow = SignIn.stepUp(poc, issuer, "/auth/step-up?intent=export-mgmt-summary&returnTo=/va-poc/exports",
                fresh(TestIdentity.of("af-5001", POC_ONE, "POC One")));
        assertThat(flow.callback().header("location")).endsWith("/va-poc/exports?stepup=done");
        poc.withCsrf();

        assertThat(requested(poc, WHOLE_CYCLE).get("status").asString()).isEqualTo("QUEUED");
    }

    @Test
    void onlyAVaPocAsksAndAnonymousIsUnauthenticated() {
        ApiClient auditor = signedInAs("export.auditor@ci.annualva.test");
        arrange.grant("AUDITOR", "export.auditor@ci.annualva.test", null, "GRANT", "2027-12-31T00:00:00Z");

        for (ApiClient other : new ApiClient[] {signedInAs(SMO_A), signedInAs(CM), auditor,
                signedInAs("export.nobody@ci.annualva.test")}) {
            assertThat(other.postJson(EXPORTS, WHOLE_CYCLE).status()).isEqualTo(403);
            assertThat(other.get(EXPORTS).status()).isEqualTo(403);
        }
        assertThat(anotherBrowser().get(EXPORTS).status()).isEqualTo(401);
    }

    @Test
    void anExportIsItsRequestersAloneAndTheOtherVaPocIsToldItDoesNotExist() {
        ApiClient poc = vaPoc();
        String id = requested(poc, WHOLE_CYCLE).get("id").asString();
        exportJob();
        ApiClient other = otherVaPoc();

        assertThat(other.get(EXPORTS + "/" + id).status()).isEqualTo(404);
        assertThat(other.download(EXPORTS + "/" + id + "/download").status()).isEqualTo(404);
        assertThat(ok(other, EXPORTS)).isEmpty();
        assertThat(other.get(EXPORTS + "/00000000-0000-0000-0000-000000000000").status()).isEqualTo(404);

        JsonNode mine = ok(poc, EXPORTS);
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).get("id").asString()).isEqualTo(id);
        assertThat(mine.get(0).get("status").asString()).isEqualTo("READY");
    }

    @Test
    void askingAndDownloadingAreAuditedWithIdsAndFixedCodesOnly() {
        ApiClient poc = vaPoc();
        String actor = userId(poc);
        String id = requested(poc, "{\"type\":\"MGMT_SUMMARY\",\"format\":\"XLSX\",\"quarter\":3}").get("id").asString();
        exportJob();

        var download = poc.download(EXPORTS + "/" + id + "/download");

        assertThat(download.status()).isEqualTo(200);
        assertThat(download.header("content-type")).isEqualTo(XLSX);
        assertThat(download.header("content-disposition"))
                .isEqualTo("attachment; filename=\"management-summary-2027-q3.xlsx\"");
        assertThat(download.header("x-content-type-options")).isEqualTo("nosniff");
        assertThat(download.header("cache-control")).contains("no-store");

        List<JsonNode> asked = entries("EXPORT_REQUESTED", actor, id);
        assertThat(asked).hasSize(1);
        assertThat(asked.get(0).get("outcome").asString()).isEqualTo("SUCCESS");
        JsonNode detail = asked.get(0).get("detail");
        assertThat(detail.get("export_id").asString()).isEqualTo(id);
        assertThat(detail.get("export_type").asString()).isEqualTo("MGMT_SUMMARY");
        assertThat(detail.get("format").asString()).isEqualTo("XLSX");
        assertThat(detail.get("classification").asString()).isEqualTo("CONFIDENTIAL");
        assertThat(detail.get("cycle").asInt()).isEqualTo(2027);
        assertThat(detail.get("quarter").asInt()).isEqualTo(3);
        assertThat(detail.get("count").asInt()).isEqualTo(1);
        List<JsonNode> downloaded = entries("EXPORT_DOWNLOADED", actor, id);
        assertThat(downloaded).hasSize(1);
        assertThat(downloaded.get(0).get("outcome").asString()).isEqualTo("SUCCESS");
        assertThat(downloaded.get(0).get("detail").get("export_id").asString()).isEqualTo(id);
        assertThat(asked.toString() + downloaded).doesNotContain("SYS-A1").doesNotContain("@ci.annualva.test");
    }
}
