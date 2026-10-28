package sg.annualva.devlocal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.RegisterXlsx;

/**
 * Ticket 63: the sample System Register in {@code scripts/dev-data/} is what gives the six personas their roles on a
 * developer's machine, so it is proven here: a VA POC persona uploads and applies it and each persona then holds the role
 * its label says, and only that one. Roles still come only from the Register apply. The file is rebuilt with
 * {@code ./mvnw test -Dtest=DevDataSampleTest -Dannualva.regenerate-dev-data=true} (from {@code backend/}).
 */
class DevDataSampleTest extends DevLocalSeam1 {

    private static final Path SAMPLE = Path.of("..", "scripts", "dev-data", "system-register.sample.xlsx");

    /** The Systems of the sample: every one has an SMO/Tech Lead and a Cyber Manager who are different people. */
    private static RegisterXlsx sample() {
        return RegisterXlsx.register()
                .row("DEV-001", "Payroll Portal", "smo.a@dev.annualva.test", "cm.a@dev.annualva.test", "Q3")
                .row("DEV-002", "Leave Management", "smo.a@dev.annualva.test", "cm.a@dev.annualva.test", "Q4")
                .row("DEV-003", "Procurement Hub", "smo.b@dev.annualva.test", "cm.a@dev.annualva.test", "Q3")
                .row("DEV-004", "Asset Register", "smo.b@dev.annualva.test", "cm.b@dev.annualva.test", "Q4")
                .row("DEV-005", "Staff Directory", "smo.a@dev.annualva.test", "cm.b@dev.annualva.test", "Q1")
                .row("DEV-006", "Intranet CMS", "smo.b@dev.annualva.test", "cm.b@dev.annualva.test", "Q2");
    }

    @Test
    void regenerate() throws IOException {
        assumeTrue(Boolean.getBoolean("annualva.regenerate-dev-data"), "set -Dannualva.regenerate-dev-data=true to rebuild");
        Files.createDirectories(SAMPLE.getParent());
        Files.write(SAMPLE, sample().build());
    }

    @Test
    void theSampleRegisterGivesEachPersonaItsRoleAndNoOther() throws IOException {
        ApiClient poc = signedInAs(POC_1);
        var upload = poc.postFile("/api/system-register/uploads", "file", "system-register.sample.xlsx", XLSX,
                Files.readAllBytes(SAMPLE));
        assertThat(upload.status()).as(upload.body()).isEqualTo(201);
        var preview = ApiClient.json(upload);
        assertThat(preview.get("canApply").asBoolean()).isTrue();
        assertThat(preview.get("counts").get("blocked").asInt()).isZero();
        var applied = poc.post("/api/system-register/previews/" + preview.get("id").asString() + "/apply");
        assertThat(applied.status()).as(applied.body()).isEqualTo(200);
        assertThat(ApiClient.json(applied).get("added").asInt()).isEqualTo(6);

        assertThat(roles(signedInAs(SMO_A))).containsExactly("SMO");
        assertThat(roles(signedInAs(SMO_B))).containsExactly("SMO");
        assertThat(roles(signedInAs(CM_A))).containsExactly("CYBER_MANAGER");
        assertThat(roles(signedInAs(CM_B))).containsExactly("CYBER_MANAGER");
        assertThat(roles(signedInAs(POC_1))).containsExactly("VA_POC");
        assertThat(roles(signedInAs(POC_2))).containsExactly("VA_POC");
        assertThat(signedInAs(SMO_A).get("/api/systems").status()).isEqualTo(200);
    }
}
