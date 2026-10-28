package sg.annualva.devlocal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import sg.annualva.app.AnnualVaApplication;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.MutableClock;
import sg.annualva.harness.RegisterXlsx;
import sg.annualva.harness.TestEndpoints;
import sg.annualva.harness.TestcontainersConfig;
import tools.jackson.databind.JsonNode;

/**
 * What the dev-local tests share (ticket 63, ADR 0058): the full application under the {@code dev-local} profile and
 * nothing else (the profile refuses to start with {@code ci}), a real PostgreSQL, the local fakes of the profile itself
 * (not the harness fakes) and a controllable clock. Plain HTTP on the loopback address and a throw-away file-store
 * folder replace what the developer's machine would have; everything else is the profile's own configuration. People
 * sign in through the persona endpoint like anyone, and roles come only from a Register a VA POC applies.
 */
@SpringBootTest(classes = AnnualVaApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "server.ssl.enabled=false",
            "server.servlet.session.cookie.secure=false",
            "annualva.scheduling.enabled=false",
            "annualva.sign-in.anonymous-sessions-per-minute=100000",
            "annualva.dev-local.scan-delay=0s",
            "annualva.scan.sync-wait=3s",
            "annualva.scan.sync-poll-interval=20ms"
        })
@ActiveProfiles("dev-local")
@Import({TestcontainersConfig.class, DevLocalSeam1.Harness.class, TestEndpoints.class})
public abstract class DevLocalSeam1 {

    static final String SMO_A = "11111111-0000-4000-8000-000000000001";
    static final String SMO_B = "11111111-0000-4000-8000-000000000002";
    static final String CM_A = "11111111-0000-4000-8000-000000000003";
    static final String CM_B = "11111111-0000-4000-8000-000000000004";
    static final String POC_1 = "11111111-0000-4000-8000-000000000005";
    static final String POC_2 = "11111111-0000-4000-8000-000000000006";

    static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private static final Path FILES = temporaryFolder();

    /** The controllable clock, as the one Clock the application reads. */
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class Harness {
        @org.springframework.context.annotation.Bean
        MutableClock mutableClock() {
            return new MutableClock();
        }

        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        java.time.Clock harnessClock(MutableClock mutableClock) {
            return mutableClock;
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    protected MutableClock clock;

    private Instant before;

    @DynamicPropertySource
    static void localFolder(DynamicPropertyRegistry registry) {
        registry.add("annualva.dev-local.file-store-dir", FILES::toString);
    }

    @BeforeEach
    void startAtTheSameInstant() {
        before = clock.instant();
        assertThat(browser().withCsrf().post("/api/test/register-reset").status()).isEqualTo(200);
    }

    @AfterEach
    void putTheClockBack() {
        clock.set(before);
    }

    protected ApiClient browser() {
        return new ApiClient(port);
    }

    protected Path fileStoreFolder() {
        return FILES;
    }

    /** Signs a browser in as a persona and gives it a fresh CSRF token; fails unless the sign-in is accepted. */
    protected ApiClient signedInAs(String persona) {
        ApiClient browser = browser().withCsrf();
        var answer = signIn(browser, persona);
        assertThat(answer.status()).as(answer.body()).isEqualTo(204);
        return browser.withCsrf();
    }

    protected static ApiClient.Response signIn(ApiClient browser, String persona) {
        return browser.postJson("/api/dev/sign-in", "{\"persona\":\"" + persona + "\"}");
    }

    protected static JsonNode session(ApiClient browser) {
        return ApiClient.json(browser.get("/api/session"));
    }

    protected static List<String> roles(ApiClient browser) {
        List<String> roles = new java.util.ArrayList<>();
        session(browser).get("roles").forEach(role -> roles.add(role.asString()));
        return roles;
    }

    /** Applies a Register of these Systems as a VA POC who has just signed in, through the real upload and apply. */
    protected void registerApplied(String... systemRows) {
        RegisterXlsx file = RegisterXlsx.register();
        for (String row : systemRows) {
            String[] cells = row.split("\\|");
            file.row(cells[0], cells[1], cells[2], cells[3], cells[4]);
        }
        ApiClient poc = signedInAs(POC_1);
        var upload = poc.postFile("/api/system-register/uploads", "file", "register.xlsx", XLSX, file.build());
        assertThat(upload.status()).as(upload.body()).isEqualTo(201);
        String id = ApiClient.json(upload).get("id").asString();
        var applied = poc.post("/api/system-register/previews/" + id + "/apply");
        assertThat(applied.status()).as(applied.body()).isEqualTo(200);
        assertThat(poc.post("/api/logout").status()).isEqualTo(204);
    }

    private static Path temporaryFolder() {
        try {
            Path folder = Files.createTempDirectory("annualva-dev-local-");
            folder.toFile().deleteOnExit();
            return folder;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
