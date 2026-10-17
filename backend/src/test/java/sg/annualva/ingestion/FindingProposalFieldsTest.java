package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * Story 35 and IM8 as-1 (ticket 19): every status needs its own field, and a proposal that lacks it, carries a field that
 * status does not use, or holds a text that is too long, empty, or a date that is not a future one is a 400 problem that
 * names the field and writes nothing. Free text has control characters and the characters that reorder it removed before it
 * is kept, and is limited in length.
 */
class FindingProposalFieldsTest extends ProposalSeam1 {

    /** The fields each status requires, with a valid value for each. */
    private static Map<String, Map<String, String>> requiredFields() {
        Map<String, Map<String, String>> byStatus = new LinkedHashMap<>();
        byStatus.put("REMEDIATED", Map.of("evidence", "Patched."));
        byStatus.put("FALSE_POSITIVE", Map.of("justification", "Not reachable."));
        byStatus.put("RISK_ACCEPTED", Map.of("reason", "No fix yet.", "approverName", "A. Tan", "approverRole", "Head of Ops"));
        byStatus.put("REMEDIATION_PLANNED", Map.of("plan", "Replace it.", "targetDate", LATER));
        return byStatus;
    }

    private static String body(String status, Map<String, String> fields) {
        StringBuilder json = new StringBuilder("{\"status\":" + quoted(status));
        fields.forEach((name, value) -> json.append(",\"").append(name).append("\":").append(quoted(value)));
        return json.append("}").toString();
    }

    private void assertNothingKept(Draft draft, String findingId) {
        assertThat(finding(draft.smo(), draft.submissionId(), "CVE-2021-44228").get("currentProposal").isNull()).isTrue();
        assertThat(ApiClient.json(draft.smo().get(proposalsOf(findingId)))).isEmpty();
    }

    @Test
    void aMissingOrUnknownStatusIsNamedAndNothingIsKept() {
        Draft draft = draftWithFindings();
        String id = findingId(draft, "CVE-2021-44228");

        assertInvalidField(propose(draft.smo(), id, "{}"), "status");
        assertInvalidField(propose(draft.smo(), id, "{\"evidence\":\"Patched.\"}"), "status");
        assertInvalidField(propose(draft.smo(), id, "{\"status\":\"CONFIRMED\"}"), "status");
        assertInvalidField(propose(draft.smo(), id, "{\"status\":\"remediated\",\"evidence\":\"x\"}"), "status");
        assertInvalidField(propose(draft.smo(), id, "{\"status\":\"\"}"), "status");
        assertThat(propose(draft.smo(), id, "{\"status\":").status()).as("not JSON").isEqualTo(400);
        assertThat(propose(draft.smo(), id, "[]").status()).as("not an object").isEqualTo(400);
        assertNothingKept(draft, id);
    }

    @Test
    void everyFieldAStatusRequiresIsNamedWhenItIsMissingOrBlank() {
        Draft draft = draftWithFindings();
        String id = findingId(draft, "CVE-2021-44228");

        requiredFields().forEach((status, fields) -> {
            assertThat(propose(draft.smo(), id, body(status, fields)).status()).as(status + " with all its fields")
                    .isEqualTo(201);
            for (String missing : fields.keySet()) {
                Map<String, String> without = new LinkedHashMap<>(fields);
                without.remove(missing);
                assertInvalidField(propose(draft.smo(), id, body(status, without)), missing);

                Map<String, String> blank = new LinkedHashMap<>(fields);
                blank.put(missing, "  \t\n ");
                assertInvalidField(propose(draft.smo(), id, body(status, blank)), missing);

                Map<String, String> controlOnly = new LinkedHashMap<>(fields);
                controlOnly.put(missing, "\u0000\u0007 ‮");
                assertInvalidField(propose(draft.smo(), id, body(status, controlOnly)), missing);

                Map<String, String> jsonNull = new LinkedHashMap<>(fields);
                jsonNull.put(missing, null);
                assertInvalidField(propose(draft.smo(), id, body(status, jsonNull)), missing);
            }
        });
        assertThat(ApiClient.json(draft.smo().get(proposalsOf(id)))).as("only the four complete ones were kept").hasSize(4);
    }

    @Test
    void aFieldThatTheStatusDoesNotUseIsNamedAndRefused() {
        Draft draft = draftWithFindings();
        String id = findingId(draft, "CVE-2021-44228");

        for (var status : requiredFields().entrySet()) {
            for (var other : requiredFields().entrySet()) {
                if (other.getKey().equals(status.getKey())) {
                    continue;
                }
                for (var field : other.getValue().entrySet()) {
                    if (status.getValue().containsKey(field.getKey())) {
                        continue;
                    }
                    Map<String, String> fields = new LinkedHashMap<>(status.getValue());
                    fields.put(field.getKey(), field.getValue());
                    assertInvalidField(propose(draft.smo(), id, body(status.getKey(), fields)), field.getKey());
                }
            }
        }
        Map<String, String> withMemo = new LinkedHashMap<>(requiredFields().get("REMEDIATED"));
        withMemo.put("approvalMemoFileId", "00000000-0000-4000-8000-000000000000");
        assertInvalidField(propose(draft.smo(), id, body("REMEDIATED", withMemo)), "approvalMemoFileId");
        assertNothingKept(draft, id);
    }

    @Test
    void aLongTextIsLimitedTo4000CharactersAndAnApproverNameOrRoleTo200CountedInCodePoints() {
        Draft draft = draftWithFindings();
        String id = findingId(draft, "CVE-2021-44228");
        String emoji = new String(Character.toChars(0x1F600));

        assertThat(propose(draft.smo(), id, remediated("x".repeat(4000))).status()).as("4000 is the most").isEqualTo(201);
        assertInvalidField(propose(draft.smo(), id, remediated("x".repeat(4001))), "evidence");
        assertThat(propose(draft.smo(), id, falsePositive(emoji.repeat(4000))).status())
                .as("an emoji is one character, though two UTF-16 units").isEqualTo(201);
        assertInvalidField(propose(draft.smo(), id, falsePositive(emoji.repeat(4001))), "justification");
        assertInvalidField(propose(draft.smo(), id, riskAccepted("x".repeat(4001), "n", "r")), "reason");
        assertInvalidField(propose(draft.smo(), id, remediationPlanned("x".repeat(4001), LATER)), "plan");
        assertThat(propose(draft.smo(), id, riskAccepted("r", "n".repeat(200), "r".repeat(200))).status()).isEqualTo(201);
        assertInvalidField(propose(draft.smo(), id, riskAccepted("r", "n".repeat(201), "r")), "approverName");
        assertInvalidField(propose(draft.smo(), id, riskAccepted("r", "n", "r".repeat(201))), "approverRole");
        assertThat(ApiClient.json(draft.smo().get(proposalsOf(id)))).as("only the three that fit were kept").hasSize(3);
    }

    @Test
    void controlCharactersAndTheCharactersThatReorderTextAreRemovedBeforeItIsKept() {
        Draft draft = draftWithFindings();
        String id = findingId(draft, "CVE-2021-44228");

        JsonNode text = proposed(draft.smo(), id,
                remediated("  one\u0000\u0007 two\r\nthree\tfour‮five⁦six​seven﻿  "));
        JsonNode line = proposed(draft.smo(), id,
                riskAccepted("r", "A.\u0000 Tan\nJr.‮", "\tHead of\r\nOps\u0085"));

        assertThat(text.get("evidence").asString()).as("line breaks and tabs stay in a long text, the rest goes")
                .isEqualTo("one two\nthree\tfourfivesixseven");
        assertThat(line.get("approverName").asString()).as("a name is one line").isEqualTo("A. Tan Jr.");
        assertThat(line.get("approverRole").asString()).isEqualTo("Head of Ops");
        JsonNode current = finding(draft.smo(), draft.submissionId(), "CVE-2021-44228").get("currentProposal");
        assertThat(current.get("approverName").asString()).as("what is kept is what was cleaned").isEqualTo("A. Tan Jr.");
    }

    /** What the first cleaner missed: tag characters, word joiner and invisible operators, soft hyphen, private use, unassigned. */
    private static String hidden() {
        int[] codePoints = {0xE0041, 0x2060, 0x2061, 0x2064, 0x00AD, 0x180E, 0xFFF9, 0xE000, 0x0378, 0xF0000};
        return new String(codePoints, 0, codePoints.length);
    }

    @Test
    void invisibleFormatPrivateUseAndUnassignedCharactersAreRemovedFromEveryTextField() {
        Draft draft = draftWithFindings();
        String id = findingId(draft, "CVE-2021-44228");

        requiredFields().forEach((status, fields) -> {
            Map<String, String> typed = new LinkedHashMap<>(fields);
            typed.replaceAll((name, value) -> name.equals("targetDate") ? value : "a" + hidden() + "b" + hidden());
            JsonNode kept = proposed(draft.smo(), id, body(status, typed));
            fields.forEach((name, value) -> {
                if (!name.equals("targetDate")) {
                    assertThat(kept.get(name).asString()).as(status + " " + name).isEqualTo("ab");
                }
            });
        });
    }

    @Test
    void aTextFieldMadeOnlyOfInvisibleCharactersIsMissing() {
        Draft draft = draftWithFindings();
        String id = findingId(draft, "CVE-2021-44228");

        requiredFields().forEach((status, fields) -> fields.keySet().stream().filter(name -> !name.equals("targetDate"))
                .forEach(name -> {
                    Map<String, String> typed = new LinkedHashMap<>(fields);
                    typed.put(name, " " + hidden() + " ");
                    assertInvalidField(propose(draft.smo(), id, body(status, typed)), name);
                }));
        assertNothingKept(draft, id);
    }

    @Test
    void aLoneSurrogateIsRemovedAndNeverBecomesAServerError() {
        Draft draft = draftWithFindings();
        String id = findingId(draft, "CVE-2021-44228");

        var response = draft.smo().postJson(proposalsOf(id), "{\"status\":\"REMEDIATED\",\"evidence\":\"a\\ud800b\\udc00c\"}");

        assertThat(response.status()).as(response.body()).isEqualTo(201);
        assertThat(ApiClient.json(response).get("evidence").asString()).isEqualTo("abc");
    }

    @Test
    void theTargetDateMustBeAWellFormedDateAfterTodayAndWithinTenYears() {
        Draft draft = draftWithFindings();
        String id = findingId(draft, "CVE-2021-44228");

        for (String bad : new String[] {"2027-08-02", "2027-08-01", "2020-01-01", "2027-8-3", "03/08/2027", "2027-02-30",
                "2027-13-01", "tomorrow", "", " 2027-12-31", "2027-12-31T00:00:00", "+12027-12-31", "2037-08-03",
                "9999-12-31"}) {
            assertInvalidField(propose(draft.smo(), id, remediationPlanned("Soon.", bad)), "targetDate");
        }
        assertThat(propose(draft.smo(), id, remediationPlanned("Tomorrow.", "2027-08-03")).status()).isEqualTo(201);
        assertThat(propose(draft.smo(), id, remediationPlanned("Ten years.", "2037-08-02")).status()).isEqualTo(201);
        assertThat(ApiClient.json(draft.smo().get(proposalsOf(id)))).hasSize(2);
        assertThat(propose(draft.smo(), id,
                "{\"status\":\"REMEDIATION_PLANNED\",\"plan\":\"Soon.\",\"targetDate\":20271231}").status())
                .as("a number is not a date").isEqualTo(400);
    }

    @Test
    void theApprovalMemoMustBeTheIdOfAFile() {
        Draft draft = draftWithFindings();
        String id = findingId(draft, "CVE-2021-44228");

        for (String bad : new String[] {"", "not-an-id", "1-1-1-1-1", "00000000-0000-4000-8000-00000000000g",
                "{00000000-0000-4000-8000-000000000000}"}) {
            assertInvalidField(propose(draft.smo(), id, riskAcceptedWithMemo("r", "n", "r", bad)), "approvalMemoFileId");
        }
        assertNothingKept(draft, id);
    }

    @Test
    void theFirstProblemReportedIsAlwaysTheSameOne() {
        Draft draft = draftWithFindings();
        String id = findingId(draft, "CVE-2021-44228");

        for (int attempt = 0; attempt < 3; attempt++) {
            assertInvalidField(propose(draft.smo(), id, "{\"status\":\"RISK_ACCEPTED\"}"), "reason");
            assertInvalidField(propose(draft.smo(), id, "{\"status\":\"REMEDIATION_PLANNED\"}"), "plan");
        }
    }
}
