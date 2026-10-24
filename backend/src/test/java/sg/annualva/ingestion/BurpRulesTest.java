package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import sg.annualva.harness.Samples;
import tools.jackson.databind.JsonNode;

/**
 * Stories 21 and 25 for the Burp parser: a file that is not a Burp {@code issues} export, or whose issues cannot be told
 * apart safely, is rejected (as REJECTED_TYPE, REJECTED_SCHEMA or REJECTED_UNREADABLE) with nothing kept and nothing said back
 * about what it held; issues are only ever read where the format puts them; text read from the file is cleaned; the request
 * and response bodies are never read; the export time is read in the form Burp writes it; and no XML reader obeys a DTD or an
 * entity (the one DOCTYPE accepted is Burp's own).
 */
@ExtendWith(OutputCaptureExtension.class)
class BurpRulesTest extends BurpSeam1 {

    private static final String SENTINEL = "BURP-SENTINEL-6120";
    private static final String GOOD = issue("t", "T", APP, "/p", "/p", "High", "Certain");

    @Test
    void anExportWhoseIssuesCannotBeKeyedOrWhoseValuesAreFarTooLongIsRejectedAsSchemaAndNothingIsKept() {
        Draft draft = draft();
        Map<String, byte[]> hostile = new LinkedHashMap<>();
        hostile.put("no-type.xml", burp(issue(null, "N", APP, "/p", "/p", "High", "Certain")));
        hostile.put("empty-type.xml", burp(issue(" ", "N", APP, "/p", "/p", "High", "Certain")));
        hostile.put("no-host.xml", burp(issue("t", "N", null, "/p", "/p", "High", "Certain")));
        hostile.put("empty-host.xml", burp(issue("t", "N", " ", "/p", "/p", "High", "Certain")));
        hostile.put("empty-issue.xml", burp("<issue/>"));
        hostile.put("separator-in-the-type.xml", burp(issue("1|2", "N", APP, "/p", "/p", "High", "Certain")));
        hostile.put("separator-in-the-host.xml", burp(issue("t", "N", "https://a|b.invalid", "/p", "/p", "High", "Certain")));
        hostile.put("long-type.xml", burp(issue("1".repeat(101), "N", APP, "/p", "/p", "High", "Certain")));
        hostile.put("long-host.xml", burp(issue("t", "N", "https://" + "h".repeat(493), "/p", "/p", "High", "Certain")));
        hostile.put("long-path.xml", burp(issue("t", "N", APP, "/" + "p".repeat(1000), "/p", "High", "Certain")));
        hostile.put("long-path-with-query.xml", burp(issue("t", "N", APP, "/" + "p".repeat(1000) + "?q", "/p", "High", "Certain")));
        hostile.put("huge-path.xml", burp(issue("t", "N", APP, "/" + "p".repeat(5000), "/p", "High", "Certain")));
        hostile.put("huge-name.xml", burp(issue("t", "n".repeat(5000), APP, "/p", "/p", "High", "Certain")));
        hostile.put("huge-location.xml", burp(issue("t", "N", APP, "/p", "l".repeat(5000), "High", "Certain")));
        hostile.put("huge-severity.xml", burp(issue("t", "N", APP, "/p", "/p", "H".repeat(101), "Certain")));
        hostile.put("huge-confidence.xml", burp(issue("t", "N", APP, "/p", "/p", "High", "C".repeat(101))));
        hostile.put("markup-in-the-type.xml", burp(issue("<b>t</b>", "N", APP, "/p", "/p", "High", "Certain")));
        hostile.put("markup-in-the-host.xml", burp(issue("t", "N", "<b>" + APP + "</b>", "/p", "/p", "High", "Certain")));
        hostile.put("one-bad-issue-among-good.xml", burp(GOOD, issue(null, "N", APP, "/p", "/p", "High", "Certain"), GOOD));
        hostile.put("long-export-time.xml", burpAt("x".repeat(101), GOOD));

        for (var entry : hostile.entrySet()) {
            JsonNode file = settledBurp(draft, entry.getKey(), entry.getValue());

            assertThat(file.get("status").asString()).as(entry.getKey()).isEqualTo("REJECTED_SCHEMA");
            assertThat(stages(file)).as(entry.getKey())
                    .containsExactly("QUARANTINED", "SCAN_SUBMITTED", "SCAN_CLEAN", "TYPE_OK", "REJECTED_SCHEMA");
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
    void aTruncatedExportIsUnreadable() {
        Draft draft = draft();

        JsonNode file = settledBurp(draft, "truncated.xml", utf8("<issues><issue><type>1</type>"));

        assertThat(file.get("status").asString()).isEqualTo("REJECTED_UNREADABLE");
    }

    @Test
    void aDocumentWhoseRootIsNotIssuesIsNotOfThisTypeAndABurpExportIsNotOfAnotherScanReportsType() {
        Draft draft = draft();
        Map<String, byte[]> others = new LinkedHashMap<>();
        others.put("capital.xml", utf8("<Issues><issue/></Issues>"));
        others.put("nested.xml", utf8("<export><issues><issue/></issues></export>"));

        for (var entry : others.entrySet()) {
            JsonNode file = settledBurp(draft, entry.getKey(), entry.getValue());

            assertThat(file.get("status").asString()).as(entry.getKey()).isEqualTo("REJECTED_TYPE");
            assertThat(file.get("rejectCode").asString()).isEqualTo("TYPE");
        }
        JsonNode asConfiguration = settled(draft, SECURE_CONFIGURATION, "burp.xml", Samples.burpSample());
        assertThat(asConfiguration.get("status").asString()).isEqualTo("REJECTED_TYPE");
    }

    @Test
    void aRejectionNeverEchoesWhatTheFileSaid(CapturedOutput output) {
        Draft draft = draft();
        byte[] report = burp(issue(SENTINEL, SENTINEL, "https://" + SENTINEL + "x".repeat(500), "/" + SENTINEL, SENTINEL,
                "High", "Certain"));

        JsonNode file = settledBurp(draft, "x.xml", report);

        assertThat(file.get("status").asString()).isEqualTo("REJECTED_SCHEMA");
        assertThat(file.toString()).doesNotContain(SENTINEL);
        assertThat(draft.smo().get(filesOf(draft.submissionId())).body()).doesNotContain(SENTINEL);
        assertThat(findings(draft.smo(), draft.submissionId()).toString()).doesNotContain(SENTINEL);
        // The suite shares one audit log and other tests write entries dated later, so this System's entries are read
        // by System to be sure they are there; the newest page of the whole log is checked as well.
        assertThat(otherVaPoc().get("/api/audit-entries?size=200&system=" + draft.systemId()).body()).doesNotContain(SENTINEL)
                .contains("FILE_REJECTED");
        assertThat(otherVaPoc().get("/api/audit-entries?size=200").body()).doesNotContain(SENTINEL);
        assertThat(output.getAll()).as("the log").doesNotContain(SENTINEL);
    }

    @Test
    void aUtf16OrUtf32ExportIsRefusedOnPurposeWhateverItsByteOrderMarkAndNothingIsEchoed() {
        Draft draft = draft();
        String text = new String(burp(issue(SENTINEL, SENTINEL, APP, "/p", "/p", "High", "Certain")), StandardCharsets.UTF_8)
                .replace("<?xml version=\"1.0\"?>", "<?xml version=\"1.0\" encoding=\"UTF-16\"?>");
        String bom = Character.toString(0xFEFF);
        Map<String, byte[]> encoded = new LinkedHashMap<>();
        encoded.put("utf16-with-mark.xml", text.getBytes(StandardCharsets.UTF_16));
        encoded.put("utf16le-with-mark.xml", (bom + text).getBytes(StandardCharsets.UTF_16LE));
        encoded.put("utf16le-without-mark.xml", text.getBytes(StandardCharsets.UTF_16LE));
        encoded.put("utf16be-without-mark.xml", text.getBytes(StandardCharsets.UTF_16BE));
        encoded.put("utf32.xml", text.replace("UTF-16", "UTF-32").getBytes(java.nio.charset.Charset.forName("UTF-32")));
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("utf16-with-mark.xml", "REJECTED_TYPE");
        expected.put("utf16le-with-mark.xml", "REJECTED_TYPE");
        expected.put("utf16le-without-mark.xml", "REJECTED_SCHEMA");
        expected.put("utf16be-without-mark.xml", "REJECTED_TYPE");
        expected.put("utf32.xml", "REJECTED_TYPE");

        for (var entry : encoded.entrySet()) {
            JsonNode file = settledBurp(draft, entry.getKey(), entry.getValue());

            assertThat(file.get("status").asString()).as(entry.getKey()).isEqualTo(expected.get(entry.getKey()));
            assertThat(file.toString()).as(entry.getKey()).doesNotContain(SENTINEL);
        }
        assertThat(findings(draft.smo(), draft.submissionId()).get("total").asInt()).isZero();
    }


    @Test
    void issuesAreReadOnlyWhereTheFormatPutsThemSoADecoyInsideAWrapperARequestOrAnIssueIsNoFinding() {
        Draft draft = draft();
        String decoy = "<type>decoy</type><name>Decoy</name><host>https://decoy.invalid</host><path>/d</path>"
                + "<severity>High</severity><confidence>Certain</confidence>";
        byte[] report = utf8("<issues exportTime=\"Tue Jul 20 10:00:00 UTC 2027\">"
                + "<wrapper><issue>" + decoy + "</issue></wrapper>"
                + "<issue><type>real</type><name>Real</name><host>https://real.invalid</host><path>/r</path>"
                + "<severity>Low</severity><confidence>Firm</confidence>"
                + "<requestresponse><issue>" + decoy + "</issue></requestresponse>"
                + "<extra><type>decoy-type</type><host>https://decoy-host.invalid</host><path>/nested</path>"
                + "<severity>Critical</severity><confidence>Tentative</confidence></extra>"
                + "<issue>" + decoy + "</issue></issue>"
                + "<other exportTime=\"Mon Jan 01 00:00:00 UTC 2001\"/></issues>");

        JsonNode file = settledBurp(draft, "decoy.xml", report);

        assertThat(file.get("status").asString()).isEqualTo("PARSED");
        assertThat(burpSummary(findings(draft.smo(), draft.submissionId()))).containsExactly(
                Map.entry("burp|real|https://real.invalid|/r", "LOW/1/FIRM"));
        JsonNode metadata = metadata(draft.smo(), draft.submissionId(), file.get("id").asString());
        assertThat(metadata.get("target").asString()).isEqualTo("https://real.invalid");
        assertThat(metadata.get("scanDate").asString()).startsWith("2027-07-20T10:00:00");
    }

    @Test
    void textReadFromTheReportLosesControlAndDirectionCharactersAndALongNameIsCutWhereATypeAndHostKeyTheFinding() {
        Draft draft = draft();
        // A right-to-left override and a line break, written as the characters themselves (a CDATA has no references).
        String rlo = Character.toString(0x202E);
        String eol = "\n";
        byte[] report = burp(
                issue("  1" + rlo + "2" + eol + "  ", "  na" + rlo + "me" + eol + "  ", "  HTTPS://Ho" + rlo + "st" + eol + ".invalid  ",
                        "/pa" + rlo + "th" + eol, null, "High", "Certain"),
                issue("long", "L".repeat(1500), "https://" + "b".repeat(300), "/p", "l".repeat(1500), "High", "Certain"),
                issue("third", "N", "https://" + "c".repeat(300), "/p", "/p", "High", "Certain"));

        String id = settledBurp(draft, "clean.xml", report).get("id").asString();

        JsonNode page = findings(draft.smo(), draft.submissionId());
        assertThat(burpSummary(page).keySet()).containsExactlyInAnyOrder("burp|12|https://host.invalid|/path",
                "burp|long|https://" + "b".repeat(300) + "|/p", "burp|third|https://" + "c".repeat(300) + "|/p");
        assertThat(byIdentifier(page, "name").get("packageOrRule").asString()).isEqualTo("https://host.invalid/path");
        assertThat(byIdentifier(page, "L".repeat(1000)).get("identifier").asString())
                .as("a name only describes: cut, not refused").hasSize(1000);
        assertThat(metadata(draft.smo(), draft.submissionId(), id).get("target").asString()).as("cleaned, then cut to 200")
                .startsWith("https://host.invalid, https://bbbb").hasSize(200);
    }

    @Test
    void theRequestsAndResponsesAreSkippedWhateverTheyHoldAndNothingFromThemOrTheIssueIsLoggedAuditedOrSaid(
            CapturedOutput output) {
        Draft draft = draft();
        String blobs = "<requestresponse><request base64=\"false\"><![CDATA[POST /login HTTP/1.1\r\nHost: " + SENTINEL
                + "\r\n\r\npassword=" + SENTINEL + "&x=<issue><type>9</type></issue>]]></request>"
                + "<response base64=\"false\"><![CDATA[HTTP/1.1 200 OK\r\n\r\n]]]]><![CDATA[>" + SENTINEL
                + " </issues> <issue>]]></response></requestresponse>";
        byte[] report = burp(issue("t", "N", APP, "/p", "/p", "High", "Certain").replace("<requestresponse>", blobs
                + "<requestresponse>"));

        JsonNode file = settledBurp(draft, "blobs.xml", report);

        assertThat(file.get("status").asString()).isEqualTo("PARSED");
        JsonNode page = findings(draft.smo(), draft.submissionId());
        assertThat(burpSummary(page)).containsExactly(Map.entry("burp|t|" + APP + "|/p", "HIGH/1/CERTAIN"));
        assertThat(page.toString()).as("the Findings carry only what the parser read").doesNotContain(SENTINEL);
        // The suite shares one audit log and other tests write entries dated later, so this System's entries are read
        // by System to be sure they are there; the newest page of the whole log is checked as well.
        assertThat(otherVaPoc().get("/api/audit-entries?size=200&system=" + draft.systemId()).body()).doesNotContain(SENTINEL)
                .contains("FILE_UPLOADED");
        assertThat(otherVaPoc().get("/api/audit-entries?size=200").body()).doesNotContain(SENTINEL);
        assertThat(output.getAll()).as("the log").doesNotContain(SENTINEL).doesNotContain("POST /login");
    }

    @Test
    void theExportTimeIsReadInTheFormBurpWritesItAndIsNullWhenTheFileSaysNothingUsable() {
        Draft draft = draft();
        Map<String, String> dates = new LinkedHashMap<>();
        dates.put("Tue Jul 20 10:00:00 SGT 2027", "2027-07-20T02:00:00");
        dates.put("  Tue Jul 20 10:00:00 SGT 2027  ", "2027-07-20T02:00:00");
        dates.put("Tue Jul 20 10:00:00 UTC 2027", "2027-07-20T10:00:00");
        dates.put("Tue Jul 20 10:00:00 GMT 2027", "2027-07-20T10:00:00");
        dates.put("Tue Jul 20 10:00:00 GMT+08:00 2027", "2027-07-20T02:00:00");
        dates.put("Tue Jul  6 09:00:00 UTC 2027", "2027-07-06T09:00:00");
        dates.put("Tue Jul 20 10:00:00 2027", "2027-07-20T10:00:00");
        dates.put("2027-07-20T10:00:00+08:00", "2027-07-20T02:00:00");
        dates.put("Mon Jul 20 10:00:00 SGT 2027", null);
        dates.put("Tue Jul 20 10:00:00 XYZ 2027", null);
        dates.put("Tue Jan  1 00:00:00 SGT 1980", null);
        dates.put("last tuesday", null);
        dates.put("", null);
        dates.put(null, null);
        for (var entry : dates.entrySet()) {
            String id = settledBurp(draft, "d.xml", burpAt(entry.getKey(), GOOD)).get("id").asString();

            JsonNode metadata = metadata(draft.smo(), draft.submissionId(), id);

            if (entry.getValue() == null) {
                assertThat(metadata.get("scanDate").isNull()).as(String.valueOf(entry.getKey())).isTrue();
            } else {
                assertThat(metadata.get("scanDate").asString()).as(entry.getKey()).startsWith(entry.getValue());
            }
            assertThat(metadata.get("scanDateIsExportTime").asBoolean()).isTrue();
        }
    }

    @Test
    void aDtdOtherThanBurpsOwnIsRejectedAndNeitherItsEntitiesNorAnExternalIdAreEverObeyed() {
        Draft draft = draft();
        String subset = "<!ELEMENT issues (issue*)>\n<!ATTLIST issues exportTime CDATA \"\">\n";
        Map<String, byte[]> hostile = new LinkedHashMap<>();
        hostile.put("internal-entity.xml", export("<!DOCTYPE issues [" + subset + "<!ENTITY e \"x\">]>", null, GOOD));
        hostile.put("external-entity.xml",
                export("<!DOCTYPE issues [" + subset + "<!ENTITY e SYSTEM \"file:///etc/hostname\">]>", null, GOOD));
        hostile.put("parameter-entity.xml",
                export("<!DOCTYPE issues [" + subset + "<!ENTITY % e SYSTEM \"http://127.0.0.1:9/x.dtd\">%e;]>", null, GOOD));
        hostile.put("external-subset.xml", export("<!DOCTYPE issues SYSTEM \"http://127.0.0.1:9/issues.dtd\">", null, GOOD));
        hostile.put("public-subset.xml",
                export("<!DOCTYPE issues PUBLIC \"-//X//EN\" \"http://127.0.0.1:9/issues.dtd\">", null, GOOD));
        hostile.put("external-subset-and-internal.xml",
                export("<!DOCTYPE issues SYSTEM \"http://127.0.0.1:9/issues.dtd\" [" + subset + "]>", null, GOOD));
        hostile.put("notation.xml", export("<!DOCTYPE issues [" + subset + "<!NOTATION n SYSTEM \"x\">]>", null, GOOD));
        hostile.put("comment-in-the-subset.xml", export("<!DOCTYPE issues [" + subset + "<!-- x -->]>", null, GOOD));
        hostile.put("another-name.xml", export("<!DOCTYPE other [" + subset + "]>", null, GOOD));
        hostile.put("a-declaration-with-an-entity-reference.xml",
                export("<!DOCTYPE issues [<!ELEMENT issues (&e;)>]>", null, GOOD));
        hostile.put("too-long.xml", export("<!DOCTYPE issues [" + "<!ELEMENT x (#PCDATA)>\n".repeat(3000) + "]>", null, GOOD));
        hostile.put("entity-before-a-large-doctype.xml", export("<!DOCTYPE issues [<!ENTITY e \"x\">"
                + "<!ELEMENT x (#PCDATA)>\n".repeat(1000) + "]>", null, GOOD));
        hostile.put("processing-instruction.xml", export("<?stylesheet href=\"x\"?>\n" + BURP_DOCTYPE, null, GOOD));
        hostile.put("doctype-behind-a-comment.xml", export("<!-- <!DOCTYPE issues []> -->\n<!DOCTYPE issues [" + subset
                + "<!ENTITY e \"x\">]>", null, GOOD));
        hostile.put("second-doctype.xml", export(BURP_DOCTYPE + BURP_DOCTYPE, null, GOOD));
        hostile.put("doctype-after-the-root.xml", utf8("<issues>" + GOOD + "</issues><!DOCTYPE issues []>"));
        hostile.put("entity-used.xml", export("<!DOCTYPE issues [" + subset + "<!ENTITY e \"c.entity\">]>", null,
                issue("&e;", "N", APP, "/p", "/p", "High", "Certain")));
        hostile.put("external-entity-used.xml", export("<!DOCTYPE issues [" + subset
                + "<!ENTITY xxe SYSTEM \"file:///etc/hostname\">]>", null, issue("t", "N", "&xxe;", "/p", "/p", "High", "Certain")));
        hostile.put("bomb.xml", export("<!DOCTYPE issues [" + subset + "<!ENTITY a \"aaaaaaaaaa\"><!ENTITY b \"&a;&a;&a;&a;&a;&a;&a;&a;\">"
                + "<!ENTITY c \"&b;&b;&b;&b;&b;&b;&b;&b;\">]>", null, issue("t", "&c;", APP, "/p", "/p", "High", "Certain")));

        for (var entry : hostile.entrySet()) {
            JsonNode file = settledBurp(draft, entry.getKey(), entry.getValue());

            assertThat(file.get("status").asString()).as(entry.getKey()).startsWith("REJECTED_");
            assertThat(file.get("status").asString()).as(entry.getKey()).isIn("REJECTED_SCHEMA", "REJECTED_UNREADABLE", "REJECTED_TYPE");
        }
        assertThat(findings(draft.smo(), draft.submissionId()).get("total").asInt()).isZero();
        assertThat(findings(draft.smo(), draft.submissionId()).toString()).doesNotContain("c.entity");
    }

    @Test
    void burpsOwnDoctypeAnEmptyOneNoneAndCommentsAroundItAreAllAccepted() {
        Draft draft = draft();
        Map<String, byte[]> accepted = new LinkedHashMap<>();
        accepted.put("burp.xml", burp(GOOD));
        accepted.put("sample.xml", Samples.burpSample());
        accepted.put("empty-doctype.xml", export("<!DOCTYPE issues>", null, GOOD));
        accepted.put("no-doctype.xml", export("", null, GOOD));
        accepted.put("bare-subset.xml", export("<!DOCTYPE issues []>", null, GOOD));
        accepted.put("comments-in-the-prolog.xml", export("<!-- before -->\n<!-- " + "x".repeat(20_000) + " -->\n"
                + BURP_DOCTYPE + "<!-- after -->\n", null, GOOD));
        // The reader reports only the end of a large DOCTYPE; the check is made on what the file says, not on that.
        accepted.put("large-doctype.xml", export("<!DOCTYPE issues [" + "<!ELEMENT x (#PCDATA)>\n".repeat(800) + "]>", null,
                GOOD));
        accepted.put("byte-order-mark.xml", utf8(Character.toString(0xFEFF) + new String(burp(GOOD), java.nio.charset.StandardCharsets.UTF_8)));

        for (var entry : accepted.entrySet()) {
            JsonNode file = settledBurp(draft, entry.getKey(), entry.getValue());

            assertThat(file.get("status").asString()).as(entry.getKey()).isEqualTo("PARSED");
        }
    }
}
