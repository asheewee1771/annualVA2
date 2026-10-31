package sg.annualva.adapters.s3;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import sg.annualva.boundary.FileStore;
import sg.annualva.harness.InMemoryFileStore;
import sg.annualva.harness.Seam1Test;

/** Ticket 51, ADR 0059: under the Seam 1 profile (ci) the S3 store and its settings do not exist; the harness store is used. */
class S3FileStoreAbsentTest extends Seam1Test {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private FileStore files;

    @Test
    void noS3BeanIsInTheContext() {
        assertThat(context.getBeansOfType(FileStore.class).values())
                .noneSatisfy(bean -> assertThat(bean.getClass().getPackageName()).isEqualTo("sg.annualva.adapters.s3"));
        assertThat(context.getBeanNamesForType(AwsProperties.class)).isEmpty();
    }

    @Test
    void theHarnessStoreIsTheOneThatIsInjected() {
        assertThat(files).isInstanceOf(InMemoryFileStore.class);
    }
}
