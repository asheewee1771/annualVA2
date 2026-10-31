package sg.annualva.adapters.s3;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import sg.annualva.boundary.FileStore;

/**
 * Ticket 51, ADR 0059: exactly one {@code FileStore} per profile. The two classes that can be the production store (the S3
 * store and the fail-closed stand-in) are scanned alone, with the profile and the switch the way each environment sets
 * them; the dev-local and Seam 1 profiles' own stores are covered by {@code DevLocalWiringTest} and the harness.
 */
class S3FileStoreWiringTest {

    @Configuration(proxyBeanMethods = false)
    @ComponentScan({"sg.annualva.adapters.s3", "sg.annualva.adapters.pending"})
    @ConfigurationPropertiesScan("sg.annualva.adapters.s3")
    static class Scan {}

    private static final String[] S3 = {
        "annualva.aws.endpoint=http://localhost:4566", "annualva.aws.region=us-east-1",
        "annualva.aws.quarantine-bucket=q", "annualva.aws.clean-bucket=c", "annualva.aws.export-bucket=e",
        "annualva.aws.kms-key-alias=alias/k"
    };

    private ApplicationContextRunner runner(String profile, String... properties) {
        return new ApplicationContextRunner().withUserConfiguration(Scan.class)
                .withInitializer(context -> context.getEnvironment().setActiveProfiles(profile))
                .withPropertyValues(properties);
    }

    private static String[] with(String... extra) {
        String[] all = Arrays.copyOf(S3, S3.length + extra.length);
        System.arraycopy(extra, 0, all, S3.length, extra.length);
        return all;
    }

    @Test
    void whereTheStoreIsNotSwitchedOnTheFailClosedStandInIsTheOnlyFileStore() {
        runner("sit", S3).run(context -> {
            assertThat(context.getBeansOfType(FileStore.class).values())
                    .singleElement().satisfies(bean -> assertThat(bean.getClass().getSimpleName()).isEqualTo("UnwiredFileStore"));
        });
    }

    @Test
    void switchedOnTheS3StoreIsTheOnlyFileStore() {
        runner("dev-mcc", with("annualva.aws.enabled=true")).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(FileStore.class).values())
                    .singleElement().satisfies(bean -> assertThat(bean.getClass().getSimpleName()).isEqualTo("S3FileStore"));
        });
    }

    @Test
    void switchedOffExplicitlyTheStandInStays() {
        runner("dev-mcc", with("annualva.aws.enabled=false")).run(context -> assertThat(context.getBeansOfType(FileStore.class))
                .hasSize(1).containsKey("unwiredFileStore"));
    }

    @Test
    void underDevLocalNeitherExistsWhateverTheSwitchSays() {
        runner("dev-local", with("annualva.aws.enabled=true")).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(FileStore.class)).isEmpty();
        });
    }

    @Test
    void switchedOnWithoutABucketTheApplicationDoesNotStart() {
        runner("dev-mcc", "annualva.aws.enabled=true", "annualva.aws.region=us-east-1", "annualva.aws.kms-key-alias=alias/k")
                .run(context -> assertThat(context).hasFailed());
    }
}
