package sg.annualva.harness;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import sg.annualva.app.AnnualVaApplication;

/**
 * Base class for every Seam 1 test: the full application on a random port, a real PostgreSQL,
 * and only the boundary adapters faked. Tests drive HTTP and assert on observable results.
 */
@SpringBootTest(classes = AnnualVaApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("ci")
@Import({TestcontainersConfig.class, HarnessConfig.class, TestEndpoints.class})
public abstract class Seam1Test {

    @LocalServerPort
    private int port;

    @Autowired
    protected MutableClock clock;

    protected ApiClient api;

    @BeforeEach
    void freshClient() {
        api = new ApiClient(port);
    }
}
