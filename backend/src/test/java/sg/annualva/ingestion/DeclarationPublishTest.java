package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.SignIn;
import sg.annualva.harness.TestIdentity;
import tools.jackson.databind.JsonNode;

/**
 * Stories 40 and 41 (ticket 61, the decision of 2026-10-24): a VA POC publishes each new version of the Declaration text in
 * the app, after a fresh sign-in and with no second approver. A version is current at once, every earlier version stays
 * as it is, and a submit that accepted an older version is refused as {@code declaration-changed} (ADR 0032). Publishing,
 * and every refusal of it, is audited by the version number and fixed codes only, never the wording.
 */
class DeclarationPublishTest extends CycleLifecycleSeam1 {

    static final String VERSIONS = "/api/declaration-text/versions";
    static final String PUBLISHED_ACTION = "DECLARATION_PUBLISHED";
    static final String REFUSED_ACTION = "REQUEST_REFUSED";
    /** Carried by every wording a test sends, so no audit entry may hold it. */
    static final String SENTINEL = "SENTINEL-T61-WORDING";
    static final String AGREED = SENTINEL + " I declare that what this VA Submission holds is accurate.\nAnd complete.";
    /** Characters the one text rule removes: a control character and two formatting characters. */
    static final String BELL = Character.toString(0x07);
    static final String RIGHT_TO_LEFT_OVERRIDE = Character.toString(0x202E);
    static final String ZERO_WIDTH_SPACE = Character.toString(0x200B);
    /** A letter outside the Basic Multilingual Plane: one character, two UTF-16 units. */
    static final String BOLD_A = Character.toString(0x1D400);

    @Test
    void aVaPocSeesThePlaceholderAsTheOneCurrentVersionBeforeAnyIsPublished() {
        JsonNode versions = versions(vaPoc());

        assertThat(versions).hasSize(1);
        JsonNode placeholder = versions.get(0);
        assertThat(placeholder.get("version").asInt()).isEqualTo(1);
        assertThat(placeholder.get("text").asString()).startsWith("PLACEHOLDER WORDING, NOT YET AGREED.");
        assertThat(placeholder.get("placeholder").asBoolean()).isTrue();
        assertThat(placeholder.get("current").asBoolean()).isTrue();
        assertThat(placeholder.get("publishedBy").isNull()).as("seeded with the app, published by no one").isTrue();
        assertThat(placeholder.get("publishedByName").isNull()).isTrue();
        assertThat(placeholder.get("publishedAt").isNull()).isTrue();
        assertThat(placeholder.get("submissionCount").asInt()).isZero();
    }

    @Test
    void aPublishedVersionIsCurrentAtOnceAndEveryEarlierVersionAndAcceptedWordingStaysAsItWas() {
        Draft draft = readyDraft();
        String placeholderWording = ApiClient.json(draft.smo().get(DECLARATION_TEXT)).get("text").asString();
        submitted(draft.smo(), draft.submissionId());
        ApiClient poc = vaPoc();
        clock.advance(Duration.ofMinutes(1));

        JsonNode answered = published(poc, AGREED);

        assertThat(answered.get("version").asInt()).isEqualTo(2);
        assertThat(answered.get("text").asString()).isEqualTo(AGREED);
        assertThat(answered.get("placeholder").asBoolean()).isFalse();
        assertThat(answered.get("current").asBoolean()).isTrue();
        assertThat(answered.get("publishedBy").asString()).isEqualTo(userId(poc));
        assertThat(answered.get("publishedByName").asString()).isEqualTo("POC One");
        assertThat(answered.get("publishedAt").asString()).isEqualTo(clock.instant().toString());
        assertThat(answered.get("submissionCount").asInt()).isZero();
        JsonNode current = ApiClient.json(draft.smo().get(DECLARATION_TEXT));
        assertThat(current.get("version").asInt()).as("current at once, with no effective date").isEqualTo(2);
        assertThat(current.get("text").asString()).isEqualTo(AGREED);
        assertThat(current.get("placeholder").asBoolean()).isFalse();
        JsonNode versions = versions(poc);
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0)).as("newest first, as published").isEqualTo(answered);
        JsonNode superseded = versions.get(1);
        assertThat(superseded.get("version").asInt()).isEqualTo(1);
        assertThat(superseded.get("current").asBoolean()).isFalse();
        assertThat(superseded.get("text").asString()).as("an earlier version is never changed")
                .isEqualTo(placeholderWording);
        assertThat(superseded.get("submissionCount").asInt()).as("the VA Submission that accepted it").isEqualTo(1);
        JsonNode accepted = submission(draft.smo(), draft.submissionId()).get("declaration");
        assertThat(accepted.get("version").asInt()).isEqualTo(1);
        assertThat(accepted.get("text").asString()).as("the wording it accepted").isEqualTo(placeholderWording);

        JsonNode third = published(poc, SENTINEL + " A later wording.");

        assertThat(third.get("version").asInt()).isEqualTo(3);
        JsonNode after = versions(poc);
        assertThat(after).extracting(version -> version.get("version").asInt()).containsExactly(3, 2, 1);
        assertThat(after).extracting(version -> version.get("current").asBoolean()).containsExactly(true, false, false);
        assertThat(after.get(1).get("text").asString()).isEqualTo(AGREED);
    }

    @Test
    void aSubmitThatAcceptedAnOlderVersionIsRefusedAsChangedAndPassesOnceTheNewOneIsTicked() {
        Draft draft = readyDraft();
        String sub = draft.submissionId();
        int shown = currentVersion(draft.smo());
        published(vaPoc(), AGREED);

        var refused = submit(draft.smo(), sub, declaration(true, shown));

        assertThat(codes(refusalReasons(refused))).containsExactly("declaration-changed");
        assertThat(submission(draft.smo(), sub).get("state").asString()).as("nothing changed").isEqualTo("DRAFT");
        JsonNode resubmitted = submitted(draft.smo(), sub);
        assertThat(resubmitted.get("state").asString()).isEqualTo("IN_REVIEW");
        assertThat(resubmitted.get("declaration").get("version").asInt()).isEqualTo(2);
        assertThat(resubmitted.get("declaration").get("text").asString()).isEqualTo(AGREED);
        JsonNode versions = versions(vaPoc());
        assertThat(versions.get(0).get("submissionCount").asInt()).isEqualTo(1);
        assertThat(versions.get(1).get("submissionCount").asInt()).isZero();
    }

    @Test
    void publishingNeedsAFreshSignInAndIsAuditedByTheVersionNumberOnly() {
        ApiClient poc = vaPoc();
        clock.advance(Duration.ofMinutes(6));
        Set<String> refusedBefore = auditIds(REFUSED_ACTION);

        var refused = publish(poc, AGREED);

        assertThat(refused.status()).isEqualTo(403);
        assertThat(ApiClient.json(refused).get("stepUpPath").asString())
                .isEqualTo("/auth/step-up?intent=declaration-publish");
        assertThat(versions(poc)).as("nothing was published").hasSize(1);
        List<JsonNode> refusals = writtenSince(REFUSED_ACTION, refusedBefore);
        assertThat(refusals).hasSize(1);
        assertThat(refusals.getFirst().get("actorId").asString()).isEqualTo(userId(poc));
        assertThat(refusals.getFirst().get("detail").get("intent").asString()).isEqualTo("declaration-publish");

        SignIn.stepUp(poc, issuer, "/auth/step-up?intent=declaration-publish&returnTo=/va-poc/declaration",
                fresh(TestIdentity.of("af-5001", POC_ONE, "POC One")));
        poc.withCsrf();
        Set<String> before = auditIds(PUBLISHED_ACTION);
        JsonNode answered = published(poc, AGREED);

        List<JsonNode> entries = writtenSince(PUBLISHED_ACTION, before);
        assertThat(entries).hasSize(1);
        JsonNode entry = entries.getFirst();
        assertThat(entry.get("outcome").asString()).isEqualTo("SUCCESS");
        assertThat(entry.get("actorId").asString()).isEqualTo(userId(poc));
        assertThat(entry.get("actorRole").asString()).isEqualTo("VA_POC");
        assertThat(entry.get("systemId").isNull()).as("about no one System").isTrue();
        assertThat(entry.get("detail").propertyNames()).containsExactly("declaration_version");
        assertThat(entry.get("detail").get("declaration_version").asInt()).isEqualTo(answered.get("version").asInt());
        assertThat(entry.toString()).as("never the wording").doesNotContain(SENTINEL);
    }

    @Test
    void onlyAVaPocReadsOrPublishesTheVersions() {
        Draft draft = draft();

        ApiClient nobody = signedInAs("declaration.nobody@ci.annualva.test");
        for (ApiClient other : new ApiClient[] {draft.smo(), signedInAs(CM), nobody}) {
            assertThat(other.get(VERSIONS).status()).isEqualTo(403);
            assertThat(publish(other, AGREED).status()).isEqualTo(403);
        }
        ApiClient signedOut = anotherBrowser().withCsrf();
        assertThat(signedOut.get(VERSIONS).status()).isEqualTo(401);
        assertThat(publish(signedOut, AGREED).status()).isEqualTo(401);
        assertThat(versions(vaPoc())).as("nothing was published").hasSize(1);
    }

    @Test
    void anyoneButAVaPocIsRefusedForTheRoleBeforeAnOverLongBodyIsRead() {
        ApiClient smo = draft().smo();
        Set<String> refusedBefore = auditIds(REFUSED_ACTION);
        Set<String> publishedBefore = auditIds(PUBLISHED_ACTION);

        var refused = publish(smo, SENTINEL + "x".repeat(8001 - SENTINEL.length()));

        assertThat(refused.status()).as(refused.body()).isEqualTo(403);
        assertThat(ApiClient.json(refused).get("type").asString()).isEqualTo("https://annualva/problems/forbidden");
        List<JsonNode> refusals = writtenSince(REFUSED_ACTION, refusedBefore);
        assertThat(refusals).hasSize(1);
        assertThat(refusals.getFirst().get("actorId").asString()).isEqualTo(userId(smo));
        assertThat(refusals.getFirst().get("detail").get("reason").asString()).isEqualTo("forbidden");
        assertThat(refusals.getFirst().get("detail").get("status").asInt()).isEqualTo(403);
        assertThat(writtenSince(PUBLISHED_ACTION, publishedBefore)).as("not filed as a publish").isEmpty();
    }

    @Test
    void theWordingIsCleanedAndCheckedForLengthAndEveryRefusalIsAuditedWithoutIt() {
        ApiClient poc = vaPoc();
        Set<String> before = auditIds(PUBLISHED_ACTION);

        assertInvalidField(poc.postJson(VERSIONS, "{}"), "text");
        assertInvalidField(publish(poc, " " + BELL + RIGHT_TO_LEFT_OVERRIDE + ZERO_WIDTH_SPACE + " \r\n\t "), "text");
        var tooLong = publish(poc, SENTINEL + "x".repeat(4001 - SENTINEL.length()));
        // Short once cleaned, so only the bound on the body (twice the rule's length) refuses it.
        var sentTooLong = publish(poc, SENTINEL + ZERO_WIDTH_SPACE.repeat(8001 - SENTINEL.length()));
        assertInvalidField(tooLong, "text");
        assertInvalidField(sentTooLong, "text");
        assertThat(ApiClient.json(sentTooLong).get("detail")).as("the bound on the body answers as the rule does")
                .isEqualTo(ApiClient.json(tooLong).get("detail"));
        assertThat(versions(poc)).as("a refused wording is not published").hasSize(1);

        JsonNode cleaned = published(poc,
                SENTINEL + " line one." + RIGHT_TO_LEFT_OVERRIDE + "\r\n\tLine two." + ZERO_WIDTH_SPACE + BELL);
        JsonNode longest = published(poc, SENTINEL + "y".repeat(4000 - SENTINEL.length()));
        JsonNode astral = published(poc, SENTINEL + BOLD_A.repeat(4000 - SENTINEL.length()));

        assertThat(cleaned.get("text").asString()).as("control and formatting characters go; new lines and tabs stay")
                .isEqualTo(SENTINEL + " line one.\n\tLine two.");
        assertThat(longest.get("text").asString()).hasSize(4000);
        String fourThousandCodePoints = astral.get("text").asString();
        assertThat(fourThousandCodePoints.codePointCount(0, fourThousandCodePoints.length()))
                .as("counted in characters, not UTF-16 units, wherever it is checked").isEqualTo(4000);
        List<JsonNode> entries = writtenSince(PUBLISHED_ACTION, before);
        assertThat(entries).extracting(entry -> entry.get("outcome").asString())
                .containsExactly("SUCCESS", "SUCCESS", "SUCCESS", "REFUSED", "REFUSED", "REFUSED", "REFUSED");
        assertThat(entries).filteredOn(entry -> entry.get("outcome").asString().equals("REFUSED"))
                .allSatisfy(entry -> {
                    assertThat(entry.get("detail").propertyNames()).containsExactly("reason");
                    assertThat(entry.get("detail").get("reason").asString()).isEqualTo("invalid-field");
                    assertThat(entry.get("actorId").asString()).isEqualTo(userId(poc));
                });
        assertThat(entries).allSatisfy(entry -> assertThat(entry.toString()).doesNotContain(SENTINEL));
    }

    /** Every version as a VA POC reads them, newest first. */
    static JsonNode versions(ApiClient client) {
        var response = client.get(VERSIONS);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        assertThat(response.header("Cache-Control")).contains("no-store");
        return ApiClient.json(response);
    }

    static ApiClient.Response publish(ApiClient client, String text) {
        return client.postJson(VERSIONS, ApiClient.toJson(Map.of("text", text)));
    }

    /** Publishes the wording and expects it to be the new current version (201); the version as answered. */
    static JsonNode published(ApiClient client, String text) {
        var response = publish(client, text);
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return ApiClient.json(response);
    }
}
