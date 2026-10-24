package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.Samples;
import tools.jackson.databind.JsonNode;

/**
 * Stories 21 and 25 for the Nessus parser: a file that is not a {@code NessusClientData_v2} audit, or whose results cannot be
 * told apart safely, is rejected (as REJECTED_TYPE or REJECTED_SCHEMA) with nothing kept and nothing said back about what it
 * held; results are only ever read where the format puts them; text read from the file is cleaned; the date is read in every
 * form the tools write; and no XML reader obeys a DTD or an entity.
 */
class NessusRulesTest extends NessusSeam1 {

    private static final String SENTINEL = "NESSUS-SENTINEL-4471";
    private static final String CM = "http://www.nessus.org/cm";

    private static final String FAILED = check("c.ok", "Ok", "FAILED", "3");

    @Test
    void aFileThatIsNotANessusAuditIsRejectedAsTypeOrSchemaAndNothingIsKept() {
        Draft draft = draft();
        Map<String, byte[]> hostile = new LinkedHashMap<>();
        hostile.put("no-report.nessus", utf8("<NessusClientData_v2><Policy/></NessusClientData_v2>"));
        hostile.put("report-in-the-wrong-place.nessus", utf8("<NessusClientData_v2 xmlns:cm=\"" + CM + "\"><Policy><Report>"
                + host("h", null, FAILED) + "</Report></Policy></NessusClientData_v2>"));
        hostile.put("no-id-or-name.nessus", nessus(host("h", null, check(null, null, "FAILED", "3"))));
        hostile.put("no-id-or-name-on-an-error.nessus", nessus(host("h", null, check(null, null, "ERROR", "3"))));
        hostile.put("empty-id-and-name.nessus", nessus(host("h", null, check("", " ", "WARNING", "3"))));
        hostile.put("unnamed-host.nessus", nessus(host(null, null, FAILED)));
        hostile.put("long-id.nessus", nessus(host("h", null, check("k".repeat(1001), "Name", "FAILED", "3"))));
        hostile.put("long-name-no-id.nessus", nessus(host("h", null, check(null, "k".repeat(1001), "FAILED", "3"))));
        hostile.put("long-host.nessus", nessus(host("h".repeat(501), null)));
        hostile.put("markup-in-a-result.nessus", nessus(host("h", null,
                "<ReportItem severity=\"3\"><cm:compliance-check-id>c</cm:compliance-check-id>"
                        + "<cm:compliance-result><b>FAILED</b></cm:compliance-result></ReportItem>")));
        hostile.put("markup-in-an-id.nessus", nessus(host("h", null,
                "<ReportItem severity=\"3\"><cm:compliance-check-id><b>c</b></cm:compliance-check-id>"
                        + "<cm:compliance-result>FAILED</cm:compliance-result></ReportItem>")));
        hostile.put("huge-text-in-a-name.nessus", nessus(host("h", null, check("c", "n".repeat(5000), "FAILED", "3"))));
        hostile.put("huge-text-in-a-name-of-a-check-that-passed.nessus",
                nessus(host("h", null, check("c", "n".repeat(5000), "PASSED", "0"))));
        hostile.put("huge-result.nessus", nessus(host("h", null, check("c", "C", "F".repeat(101), "3"))));
        hostile.put("huge-host-end.nessus", nessus(host("h", "x".repeat(101), FAILED)));
        hostile.put("huge-severity.nessus", nessus(host("h", null, check("c", "C", "FAILED", "3".repeat(101)))));
        hostile.put("truncated.nessus", utf8("<NessusClientData_v2><Report name=\"r\"><ReportHost name=\"h\">"));

        for (var entry : hostile.entrySet()) {
            JsonNode file = settledNessus(draft, entry.getKey(), entry.getValue());

            assertThat(file.get("status").asString()).as(entry.getKey()).startsWith("REJECTED_");
            assertThat(file.get("status").asString()).as(entry.getKey())
                    .isEqualTo(entry.getKey().startsWith("truncated") ? "REJECTED_UNREADABLE" : "REJECTED_SCHEMA");
            if (!entry.getKey().startsWith("truncated")) {
                assertThat(stages(file)).as(entry.getKey())
                        .containsExactly("QUARANTINED", "SCAN_SUBMITTED", "SCAN_CLEAN", "TYPE_OK", "REJECTED_SCHEMA");
            }
            assertThat(draft.smo().get(fileAt(draft.submissionId(), file.get("id").asString()) + "/metadata").status())
                    .as(entry.getKey()).isEqualTo(404);
            assertThat(draft.smo().download(fileAt(draft.submissionId(), file.get("id").asString()) + "/content").status())
                    .as("a rejected file is never served").isEqualTo(404);
        }
        assertThat(findings(draft.smo(), draft.submissionId()).get("total").asInt()).as("no Finding of a rejected file")
                .isZero();
        assertThat(fileStore.count(sg.annualva.boundary.FileStore.Zone.CLEAN)).as("no clean copy of a rejected file")
                .isZero();
    }

    @Test
    void aDocumentWhoseRootIsNotNessusClientDataV2IsNotOfThisTypeForTheScanReport() {
        Draft draft = draft();
        Map<String, byte[]> others = new LinkedHashMap<>();
        others.put("v1.nessus", utf8("<NessusClientData><Report name=\"r\"><ReportHost name=\"h\"/></Report></NessusClientData>"));
        others.put("other.nessus", utf8("<Report name=\"r\"><ReportHost name=\"h\"/></Report>"));

        for (var entry : others.entrySet()) {
            JsonNode file = settledNessus(draft, entry.getKey(), entry.getValue());

            assertThat(file.get("status").asString()).as(entry.getKey()).isEqualTo("REJECTED_TYPE");
            assertThat(file.get("rejectCode").asString()).isEqualTo("TYPE");
        }
    }

    @Test
    void aRejectionNeverEchoesWhatTheFileSaid() {
        Draft draft = draft();
        byte[] report = nessus(host(SENTINEL + "x".repeat(500), null, check(SENTINEL, SENTINEL, "FAILED", "3")));

        JsonNode file = settledNessus(draft, "x.nessus", report);

        assertThat(file.get("status").asString()).isEqualTo("REJECTED_SCHEMA");
        assertThat(file.toString()).doesNotContain(SENTINEL);
        assertThat(draft.smo().get(filesOf(draft.submissionId())).body()).doesNotContain(SENTINEL);
        assertThat(findings(draft.smo(), draft.submissionId()).toString()).doesNotContain(SENTINEL);
        // The suite shares one audit log and other tests write entries dated later, so this System's entries are read
        // by System to be sure they are there; the newest page of the whole log is checked as well.
        assertThat(otherVaPoc().get("/api/audit-entries?size=200&system=" + draft.systemId()).body()).doesNotContain(SENTINEL)
                .contains("FILE_REJECTED");
        assertThat(otherVaPoc().get("/api/audit-entries?size=200").body()).doesNotContain(SENTINEL);
    }

    @Test
    void resultsAreReadOnlyWhereTheFormatPutsThemSoADecoyInThePolicyOrOutsideAHostIsNoFinding() {
        Draft draft = draft();
        String result = "<cm:compliance-check-id>%s</cm:compliance-check-id><cm:compliance-result>FAILED</cm:compliance-result>";
        byte[] report = utf8("<NessusClientData_v2 xmlns:cm=\"" + CM + "\">"
                + "<Policy><Report><ReportHost name=\"decoy-policy\"><ReportItem severity=\"3\">" + result.formatted("d.policy")
                + "</ReportItem></ReportHost></Report></Policy>"
                + "<ReportHost name=\"decoy-root\"><ReportItem severity=\"3\">" + result.formatted("d.root")
                + "</ReportItem></ReportHost>"
                + "<Report name=\"real\">"
                + "<ReportItem severity=\"3\">" + result.formatted("d.no-host") + "</ReportItem>"
                + "<ReportHost name=\"real-host\">"
                + "<tag name=\"HOST_END\">Tue Jul 20 23:00:00 2027</tag>"
                + "<HostProperties><nested><tag name=\"HOST_END\">Tue Jul 20 22:00:00 2027</tag></nested>"
                + "<tag name=\"HOST_END\">Tue Jul 20 10:00:00 2027</tag></HostProperties>"
                + "<ReportItem severity=\"3\">" + result.formatted("real") + "<extra>" + result.formatted("d.nested")
                + "</extra></ReportItem>"
                + "<ReportHost name=\"decoy-nested-host\"><ReportItem severity=\"3\">" + result.formatted("d.nested-host")
                + "</ReportItem></ReportHost>"
                + "</ReportHost></Report></NessusClientData_v2>");

        JsonNode file = settledNessus(draft, "decoy.nessus", report);

        assertThat(file.get("status").asString()).isEqualTo("PARSED");
        assertThat(findingSummary(findings(draft.smo(), draft.submissionId())))
                .containsExactly(Map.entry("nessus|real", "HIGH/1"));
        JsonNode metadata = metadata(draft.smo(), draft.submissionId(), file.get("id").asString());
        assertThat(metadata.get("target").asString()).isEqualTo("real-host");
        assertThat(metadata.get("scanDate").asString()).as("the HOST_END of the host's properties only")
                .startsWith("2027-07-20T10:00:00");
    }

    @Test
    void textReadFromTheReportLosesControlAndDirectionCharactersAndALongNameIsCutWhereAnIdKeysTheFinding() {
        Draft draft = draft();
        byte[] report = nessus(
                "<ReportHost name=\"  ho&#x202E;st&#10;a  \"><HostProperties/>"
                        + check("k&#x202E;1&#10;", "  na&#x202E;me&#10;  ", "FAILED", "3") + "</ReportHost>",
                host("b".repeat(300), null, check("long", "L".repeat(1500), "FAILED", "3")),
                host("c".repeat(300), null));

        String id = settledNessus(draft, "clean.nessus", report).get("id").asString();

        JsonNode page = findings(draft.smo(), draft.submissionId());
        assertThat(findingSummary(page).keySet()).containsExactlyInAnyOrder("nessus|k1", "nessus|long");
        assertThat(byIdentifier(page, "name").get("packageOrRule").asString()).isEqualTo("name");
        assertThat(byIdentifier(page, "L".repeat(1000)).get("identifier").asString()).as("a name only describes: cut, not refused")
                .hasSize(1000);
        assertThat(metadata(draft.smo(), draft.submissionId(), id).get("target").asString()).as("cleaned, then cut to 200")
                .startsWith("hosta, bbbb").hasSize(200);
    }

    @Test
    void theScanDateIsReadInEveryFormTheToolsWriteAndIsNullWhenTheFileSaysNothingUsable() {
        Draft draft = draft();
        Map<String, String> dates = new LinkedHashMap<>();
        dates.put("Tue Jul 20 10:03:44 2027", "2027-07-20T10:03:44");
        dates.put("  Tue Jul 20 10:03:44 2027  ", "2027-07-20T10:03:44");
        dates.put("Tue Jul  6 09:00:00 2027", "2027-07-06T09:00:00");
        dates.put("Tue Jul 6 09:00:00 2027", "2027-07-06T09:00:00");
        dates.put("2027-07-20T10:00:00Z", "2027-07-20T10:00:00");
        dates.put("2027-07-20T10:00:00+08:00", "2027-07-20T02:00:00");
        dates.put("2027-07-20T10:00:00", "2027-07-20T10:00:00");
        dates.put("Mon Jul 20 10:03:44 2027", null);
        dates.put("Tue Jul 20 25:61:00 2027", null);
        dates.put("Tue Jan  1 00:00:00 1980", null);
        dates.put("last tuesday", null);
        dates.put("", null);
        dates.put(null, null);
        for (var entry : dates.entrySet()) {
            String id = settledNessus(draft, "d.nessus", nessus(host("h.invalid", entry.getKey(), FAILED))).get("id")
                    .asString();

            JsonNode metadata = metadata(draft.smo(), draft.submissionId(), id);

            if (entry.getValue() == null) {
                assertThat(metadata.get("scanDate").isNull()).as(String.valueOf(entry.getKey())).isTrue();
            } else {
                assertThat(metadata.get("scanDate").asString()).as(entry.getKey()).startsWith(entry.getValue());
            }
            assertThat(metadata.get("target").asString()).isEqualTo("h.invalid");
        }
    }

    @Test
    void aDtdAndItsEntitiesAreNeverObeyed() {
        Draft draft = draft();
        String xxe = "<!DOCTYPE NessusClientData_v2 [<!ENTITY xxe SYSTEM \"file:///etc/hostname\">]>";
        String internal = "<!DOCTYPE NessusClientData_v2 [<!ENTITY check \"c.entity\">]>";
        String bomb = "<!DOCTYPE NessusClientData_v2 [<!ENTITY a \"aaaaaaaaaa\"><!ENTITY b \"&a;&a;&a;&a;&a;&a;&a;&a;\">"
                + "<!ENTITY c \"&b;&b;&b;&b;&b;&b;&b;&b;\">]>";
        Map<String, byte[]> documents = new LinkedHashMap<>();
        documents.put("xxe.nessus", withDoctype(xxe, "<ReportHost name=\"&xxe;\"><ReportItem severity=\"3\">"
                + "<cm:compliance-check-id>c</cm:compliance-check-id><cm:compliance-result>FAILED</cm:compliance-result>"
                + "</ReportItem></ReportHost>"));
        documents.put("internal.nessus", withDoctype(internal, "<ReportHost name=\"h\"><ReportItem severity=\"3\">"
                + "<cm:compliance-check-id>&check;</cm:compliance-check-id><cm:compliance-result>FAILED</cm:compliance-result>"
                + "</ReportItem></ReportHost>"));
        documents.put("bomb.nessus", withDoctype(bomb, "<ReportHost name=\"&c;\"/>"));

        for (var entry : documents.entrySet()) {
            JsonNode file = settledNessus(draft, entry.getKey(), entry.getValue());

            assertThat(file.get("status").asString()).as(entry.getKey()).startsWith("REJECTED_");
        }
        assertThat(findings(draft.smo(), draft.submissionId()).get("total").asInt()).isZero();
        assertThat(findings(draft.smo(), draft.submissionId()).toString()).doesNotContain("c.entity");
    }

    private static byte[] withDoctype(String doctype, String host) {
        return utf8("<?xml version=\"1.0\"?>" + doctype + "<NessusClientData_v2 xmlns:cm=\"" + CM + "\"><Report name=\"r\">"
                + host + "</Report></NessusClientData_v2>");
    }

    @Test
    void aDoctypeThatDeclaresNothingIsSkippedNotObeyed() {
        Draft draft = draft();
        byte[] report = utf8("<?xml version=\"1.0\"?><!DOCTYPE NessusClientData_v2>" + new String(nessus(host("h", null, FAILED)),
                StandardCharsets.UTF_8).replace("<?xml version=\"1.0\" ?>", ""));

        JsonNode file = settledNessus(draft, "doctype.nessus", report);

        assertThat(file.get("status").asString()).isEqualTo("PARSED");
        assertThat(findingSummary(findings(draft.smo(), draft.submissionId()))).containsExactly(Map.entry("nessus|c.ok",
                "HIGH/1"));
    }

    @Test
    void theSampleExportCarriesAProvenanceNote() {
        assertThat(new String(Samples.nessusComplianceSample(), StandardCharsets.UTF_8)).contains("PROVENANCE")
                .contains("NOT the output of a real").contains("Regenerate it from a real scan");
    }
}
