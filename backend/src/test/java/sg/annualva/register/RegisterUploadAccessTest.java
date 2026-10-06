package sg.annualva.register;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.RegisterXlsx;
import sg.annualva.harness.SignIn;
import sg.annualva.harness.TestIdentity;
import tools.jackson.databind.JsonNode;

/**
 * Only a VA POC may upload a Register file or read a preview (the permissions matrix). Everyone else is refused
 * before the file is read, and a refusal changes nothing and is recorded as a refused request.
 */
class RegisterUploadAccessTest extends RegisterSeam1 {

    private static final byte[] FILE = RegisterXlsx.register()
            .row("SYS-1", "One", "one@ci.annualva.test", "cm@ci.annualva.test", "Q1").build();

    @Test
    void anSmoACyberManagerAnAuditorAndSomeoneWithNoRoleAreForbiddenAndNoPreviewIsMade() {
        String system = arrange.system();
        arrange.grant("SMO", "access.smo@ci.annualva.test", system);
        arrange.grant("CYBER_MANAGER", "access.cm@ci.annualva.test", system);
        arrange.grant("AUDITOR", "access.auditor@ci.annualva.test", null, "GRANT", null);
        ApiClient poc = vaPoc();
        JsonNode made = preview(poc, RegisterXlsx.register()
                .row("SYS-OWN", "Own", "own@ci.annualva.test", "cm@ci.annualva.test", "Q1"));
        long previewedBefore = entries(poc, "REGISTER_PREVIEWED", null).get("total").asLong();
        long rejectedBefore = entries(poc, "REGISTER_UPLOAD_REJECTED", null).get("total").asLong();

        for (String email : new String[] {"access.smo@ci.annualva.test", "access.cm@ci.annualva.test",
            "access.auditor@ci.annualva.test", "access.norole@ci.annualva.test"}) {
            ApiClient person = signedInAs(email);

            var upload = upload(person, FILE);
            var invalidUpload = upload(person, new byte[] {1, 2, 3});
            var read = person.get(PREVIEWS + made.get("id").asString());

            assertThat(upload.status()).as(email).isEqualTo(403);
            assertThat(invalidUpload.status()).as(email + " with a bad file").isEqualTo(403);
            assertThat(read.status()).as(email + " reading").isEqualTo(403);
            assertThat(problemType(upload)).isEqualTo("https://annualva/problems/forbidden");
            assertThat(read.body()).doesNotContain("SYS-OWN").doesNotContain("own@ci.annualva.test");
        }

        assertThat(entries(poc, "REGISTER_PREVIEWED", null).get("total").asLong()).isEqualTo(previewedBefore);
        assertThat(entries(poc, "REGISTER_UPLOAD_REJECTED", null).get("total").asLong())
                .as("a refused caller's file is never read, so it is not even a rejected file").isEqualTo(rejectedBefore);
    }

    @Test
    void aRefusedUploadIsRecordedAsARefusedRequestOfThatPerson() {
        arrange.grant("SMO", "access.refused@ci.annualva.test", arrange.system());
        ApiClient smo = signedInAs("access.refused@ci.annualva.test");
        String actor = userId(smo);

        assertThat(upload(smo, FILE).status()).isEqualTo(403);

        JsonNode refusals = entries(otherVaPoc(), "REQUEST_REFUSED", actor);
        assertThat(refusals.get("total").asLong()).isEqualTo(1);
        assertThat(refusals.get("items").get(0).get("detail").get("status").asInt()).isEqualTo(403);
    }

    @Test
    void withoutASessionTheUploadAndTheReadAreUnauthenticated() {
        ApiClient nobody = anotherBrowser();

        var upload = nobody.postFile(UPLOAD, "file", "register.xlsx", XLSX, FILE);
        var read = nobody.get(PREVIEWS + UUID.randomUUID());

        assertThat(upload.status()).isIn(401, 403);
        assertThat(read.status()).isEqualTo(401);
        assertThat(problemType(read)).isEqualTo("https://annualva/problems/unauthenticated");
    }

    @Test
    void anUploadWithoutTheCsrfTokenIsRefused() {
        ApiClient noToken = anotherBrowser();
        SignIn.signedIn(noToken, issuer, TestIdentity.of("af-5001", POC_ONE, "POC One"));

        var response = noToken.postFile(UPLOAD, "file", "register.xlsx", XLSX, FILE);

        assertThat(response.status()).isEqualTo(403);
        assertThat(problemType(response)).isEqualTo("https://annualva/problems/csrf-rejected");
    }

    @Test
    void aVaPocUploadNeedsNoStepUpBecauseOnlyTheApplyIsTheSensitiveStep() {
        ApiClient poc = vaPoc();

        var response = upload(poc, FILE);

        assertThat(response.status()).isEqualTo(201);
    }

    private static String userId(ApiClient browser) {
        return ApiClient.json(browser.get("/api/session")).get("uuid").asString();
    }

    private static JsonNode entries(ApiClient reader, String action, String actor) {
        var response = reader.get("/api/audit-entries?size=200&action=" + action + (actor == null ? "" : "&actor=" + actor));
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return ApiClient.json(response);
    }
}
