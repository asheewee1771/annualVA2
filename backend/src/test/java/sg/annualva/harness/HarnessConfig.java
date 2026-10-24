package sg.annualva.harness;

import java.time.Clock;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The in-process fakes for the boundary adapters. Each fake replaces the production bean with
 * {@code @Primary}; fakes exist only on the test classpath (an architecture test checks main).
 */
@TestConfiguration(proxyBeanMethods = false)
public class HarnessConfig {

    @Bean
    MutableClock mutableClock() {
        return new MutableClock();
    }

    /** The same instance under the Clock type, so modules that inject Clock get the fake. */
    @Bean
    @Primary
    Clock harnessClock(MutableClock mutableClock) {
        return mutableClock;
    }

    /** The object store, in memory; primary over the production store, which is not wired in tests. */
    @Bean
    @Primary
    InMemoryFileStore inMemoryFileStore() {
        return new InMemoryFileStore();
    }

    /** What the VA Submission commands published, as a consumer after the commit sees it. */
    @Bean
    RecordedSubmissionEvents recordedSubmissionEvents() {
        return new RecordedSubmissionEvents();
    }

    /** What the VA Cycle closes published, as a consumer after the commit sees it. */
    @Bean
    RecordedCycleEvents recordedCycleEvents() {
        return new RecordedCycleEvents();
    }

    /** The malware scanner: clean, sanitised, blocked or never finishes, by keyword in the file name. */
    @Bean
    @Primary
    FakeMalwareScanner fakeMalwareScanner(InMemoryFileStore files) {
        return new FakeMalwareScanner(files);
    }
}
