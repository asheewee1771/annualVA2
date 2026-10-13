package sg.annualva.harness;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import sg.annualva.app.AnnualVaApplication;

/**
 * Base class for every Seam 1 test: the full application on a random port, a real PostgreSQL,
 * and only the boundary adapters faked. Tests drive HTTP and assert on observable results.
 */
@SpringBootTest(classes = AnnualVaApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("ci")
@Import({TestcontainersConfig.class, HarnessConfig.class, TestEndpoints.class})
public abstract class Seam1Test {

    /** The identity-provider fake; started before the context so the client registration can point at it. */
    protected static final FakeOidcIssuer issuer = FakeOidcIssuer.start();

    @LocalServerPort
    private int port;

    @Autowired
    protected MutableClock clock;

    /** The malware-scanner fake and the in-memory file store; both are emptied before every test. */
    @Autowired
    protected FakeMalwareScanner scanner;

    @Autowired
    protected InMemoryFileStore fileStore;

    protected ApiClient api;

    /** Arranges Systems and role grants through the test-only endpoints, from a separate browser. */
    protected Arrange arrange;

    @DynamicPropertySource
    static void identityProvider(DynamicPropertyRegistry registry) {
        issuer.registerProperties(registry);
    }

    @BeforeEach
    void freshClient() {
        scanner.reset();
        fileStore.reset();
        api = new ApiClient(port);
        arrange = new Arrange(new ApiClient(port).withCsrf());
        issuer.clientJwksUrl("http://localhost:" + port + "/.well-known/jwks.json");
    }

    /** A second browser, for tests about concurrent sessions. */
    protected ApiClient anotherBrowser() {
        return new ApiClient(port);
    }
}
