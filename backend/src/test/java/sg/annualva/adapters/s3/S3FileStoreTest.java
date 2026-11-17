package sg.annualva.adapters.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import sg.annualva.boundary.BoundaryFailureException;
import sg.annualva.boundary.FileStore.Zone;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetBucketEncryptionRequest;
import software.amazon.awssdk.services.s3.model.GetBucketLifecycleConfigurationRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

/**
 * Ticket 51, ADR 0059: the S3 file store against a real S3 and KMS (LocalStack in Testcontainers). LocalStack is set up by
 * the repository's own init scripts, so the buckets, the key and the lifecycle rules that the README run book relies on are
 * tested too. A second client, not the adapter, reads back what was stored.
 */
class S3FileStoreTest {

    private static final String QUARANTINE = "annualva-quarantine-dev";
    private static final String CLEAN = "annualva-clean-dev";
    private static final String EXPORT = "annualva-export-dev";
    private static final String ALIAS = "alias/annualva-dev";
    /** The init scripts, found from the module folder whatever folder the tests are started in. */
    private static final Path INIT = Path.of(System.getProperty("basedir", "")).toAbsolutePath().resolve("../scripts/localstack-init").normalize();

    private static LocalStackContainer localstack;
    private static S3Client inspector;

    private final List<S3FileStore> opened = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void startLocalStack() throws Exception {
        localstack = new LocalStackContainer(DockerImageName.parse("localstack/localstack:3")).withServices("s3", "kms")
                .withCopyFileToContainer(MountableFile.forHostPath(INIT.resolve("01-kms-key.sh"), 0755),
                        "/etc/localstack/init/ready.d/01-kms-key.sh")
                .withCopyFileToContainer(MountableFile.forHostPath(INIT.resolve("02-buckets.sh"), 0755),
                        "/etc/localstack/init/ready.d/02-buckets.sh");
        localstack.start();
        awaitInitScripts();
        inspector = S3Client.builder().endpointOverride(localstack.getEndpoint()).forcePathStyle(true)
                .region(Region.of(localstack.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))).build();
    }

    @AfterAll
    static void stopLocalStack() {
        if (inspector != null) {
            inspector.close();
        }
        if (localstack != null) {
            localstack.stop();
        }
    }

    @AfterEach
    void closeStores() {
        opened.forEach(S3FileStore::destroy);
    }

    private static void awaitInitScripts() throws Exception {
        var http = HttpClient.newHttpClient();
        var request = HttpRequest.newBuilder(URI.create(localstack.getEndpoint() + "/_localstack/init/ready")).build();
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (System.nanoTime() < deadline) {
            String body = http.send(request, HttpResponse.BodyHandlers.ofString()).body();
            if (body.contains("\"state\": \"ERROR\"") || body.contains("\"state\": \"FAILED\"")) {
                throw new IllegalStateException("A LocalStack init script failed: " + body);
            }
            if (body.contains("\"completed\": true")) {
                assertThat(body).as("every init script ran").contains("01-kms-key.sh", "02-buckets.sh")
                        .doesNotContain("\"state\": \"ERROR\"");
                return;
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("LocalStack did not finish its init scripts");
    }

    private AwsProperties properties(String quarantine, String clean, String export) {
        return new AwsProperties(localstack.getEndpoint().toString(), localstack.getRegion(), "test", "test", quarantine,
                clean, export, ALIAS, Duration.ofSeconds(3), Duration.ofSeconds(30), Duration.ofSeconds(60), 2);
    }

    /** Settings for a store that is not there, or that does not answer, at the given endpoint. */
    private static AwsProperties at(String endpoint, Duration timeout, int attempts) {
        return new AwsProperties(endpoint, "us-east-1", "test", "test", QUARANTINE, CLEAN, EXPORT, ALIAS, timeout, timeout,
                timeout, attempts);
    }

    private S3FileStore store(AwsProperties properties) {
        var store = new S3FileStore(properties);
        opened.add(store);
        return store;
    }

    private S3FileStore store() {
        return store(properties(QUARANTINE, CLEAN, EXPORT));
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    private static HeadObjectResponse head(String bucket, String key) {
        return inspector.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
    }

    @Test
    void keepsWhatItWasGivenAndReadsItBack() {
        String key = newKey();
        byte[] content = "report bytes".getBytes(StandardCharsets.UTF_8);

        store().put(Zone.CLEAN, key, content);

        assertThat(store().get(Zone.CLEAN, key)).isEqualTo(content);
    }

    @Test
    void keepsEachZoneInABucketOfItsOwn() {
        String key = newKey();
        var store = store();
        store.put(Zone.QUARANTINE, key, new byte[] {1});
        store.put(Zone.CLEAN, key, new byte[] {2});
        store.put(Zone.EXPORT, key, new byte[] {3});

        assertThat(store.get(Zone.QUARANTINE, key)).containsExactly(1);
        assertThat(store.get(Zone.CLEAN, key)).containsExactly(2);
        assertThat(store.get(Zone.EXPORT, key)).containsExactly(3);
        assertThat(head(QUARANTINE, key).contentLength()).isEqualTo(1);
        assertThat(head(CLEAN, key).contentLength()).isEqualTo(1);
        assertThat(head(EXPORT, key).contentLength()).isEqualTo(1);
    }

    @Test
    void aFileOfOneZoneIsNotInTheOthers() {
        String key = newKey();
        var store = store();
        store.put(Zone.QUARANTINE, key, new byte[] {1});

        assertThatThrownBy(() -> store.get(Zone.CLEAN, key)).isInstanceOf(BoundaryFailureException.class);
        assertThatThrownBy(() -> store.get(Zone.EXPORT, key)).isInstanceOf(BoundaryFailureException.class);
    }

    @Test
    void putReplacesAnEarlierCopy() {
        String key = newKey();
        var store = store();
        store.put(Zone.CLEAN, key, new byte[] {1, 1, 1});
        store.put(Zone.CLEAN, key, new byte[] {2});

        assertThat(store.get(Zone.CLEAN, key)).containsExactly(2);
    }

    @Test
    void anEmptyFileIsKept() {
        String key = newKey();
        var store = store();
        store.put(Zone.EXPORT, key, new byte[0]);

        assertThat(store.get(Zone.EXPORT, key)).isEmpty();
    }

    @Test
    void deleteRemovesTheFileAndRemovingWhatIsNotThereIsNotAnError() {
        String key = newKey();
        var store = store();
        store.put(Zone.QUARANTINE, key, new byte[] {1});

        store.delete(Zone.QUARANTINE, key);

        assertThatThrownBy(() -> store.get(Zone.QUARANTINE, key)).isInstanceOf(BoundaryFailureException.class);
        assertThatCode(() -> store.delete(Zone.QUARANTINE, key)).doesNotThrowAnyException();
    }

    @Test
    void readingWhatIsNotThereIsABoundaryFailureThatNamesNothing() {
        String key = newKey();

        assertThatThrownBy(() -> store().get(Zone.CLEAN, key)).isInstanceOf(BoundaryFailureException.class)
                .hasMessage("The file store holds nothing under this key").hasNoCause();
    }

    @Test
    void everyWriteAsksForTheKmsKeyEvenWhereTheBucketHasNoDefaultEncryption() throws Exception {
        String bucket = "plain-" + newKey().substring(0, 8);
        inspector.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        // A new bucket has no KMS default (S3 itself encrypts with its own keys, or LocalStack not at all).
        assertThat(defaultAlgorithm(bucket)).isNotEqualTo(ServerSideEncryption.AWS_KMS);
        String keyId = describeKeyId();
        var store = store(properties(bucket, bucket, bucket));

        for (Zone zone : Zone.values()) {
            String key = newKey();
            store.put(zone, key, new byte[] {7});

            HeadObjectResponse stored = head(bucket, key);
            assertThat(stored.serverSideEncryption()).as(zone.name()).isEqualTo(ServerSideEncryption.AWS_KMS);
            // Real S3 answers the key's ARN; LocalStack echoes the alias it was asked for inside an ARN.
            assertThat(stored.ssekmsKeyId()).as(zone.name()).startsWith("arn:aws:kms:").matches(id -> id.contains(keyId) || id.contains(ALIAS));
        }
    }

    @Test
    void theBucketsMadeByTheInitScriptsEncryptByDefaultAndExpireWhatIsLeftBehind() {
        for (String bucket : List.of(QUARANTINE, CLEAN, EXPORT)) {
            var rule = inspector.getBucketEncryption(GetBucketEncryptionRequest.builder().bucket(bucket).build())
                    .serverSideEncryptionConfiguration().rules().get(0).applyServerSideEncryptionByDefault();
            assertThat(rule.sseAlgorithm()).as(bucket).isEqualTo(ServerSideEncryption.AWS_KMS);
            assertThat(rule.kmsMasterKeyID()).as(bucket).isNotBlank();
        }
        assertThat(expiryDays(QUARANTINE)).isEqualTo(1);
        assertThat(expiryDays(EXPORT)).isEqualTo(2);
        assertThatThrownBy(() -> expiryDays(CLEAN)).isInstanceOf(S3Exception.class);
    }

    @Test
    void aKeyThatIsNotTheApplicationsOwnIsRefusedBeforeAnyCall() {
        var store = store(at("http://localhost:1", Duration.ofMillis(200), 1));

        for (String key : new String[] {"", "../other", "a/b", ".hidden", "name with space", "x".repeat(129)}) {
            assertThatThrownBy(() -> store.put(Zone.CLEAN, key, new byte[] {1})).hasMessage("The file store does not accept this key");
            assertThatThrownBy(() -> store.get(Zone.CLEAN, key)).hasMessage("The file store does not accept this key");
            assertThatThrownBy(() -> store.delete(Zone.CLEAN, key)).hasMessage("The file store does not accept this key");
        }
    }

    @Test
    void anUnreachableStoreIsABoundaryFailureWithNoDetail() {
        var store = store(at("http://localhost:1", Duration.ofMillis(200), 1));
        String key = newKey();

        assertThatThrownBy(() -> store.put(Zone.CLEAN, key, new byte[] {1})).isInstanceOf(BoundaryFailureException.class)
                .hasMessage("The file store could not write the file").hasNoCause();
        assertThatThrownBy(() -> store.get(Zone.CLEAN, key)).isInstanceOf(BoundaryFailureException.class)
                .hasMessage("The file store could not read the file").hasNoCause();
        assertThatThrownBy(() -> store.delete(Zone.CLEAN, key)).isInstanceOf(BoundaryFailureException.class)
                .hasMessage("The file store could not delete the file").hasNoCause();
    }

    @Test
    void aMissingBucketIsABoundaryFailureThatNamesNeitherBucketNorKey() {
        var store = store(properties("no-such-bucket", CLEAN, EXPORT));
        String key = newKey();

        assertThatThrownBy(() -> store.put(Zone.QUARANTINE, key, new byte[] {1})).isInstanceOf(BoundaryFailureException.class)
                .hasMessageNotContainingAny("no-such-bucket", key);
        assertThatThrownBy(() -> store.get(Zone.QUARANTINE, key)).isInstanceOf(BoundaryFailureException.class)
                .hasMessageNotContainingAny("no-such-bucket", key);
    }

    @Test
    void aStoreThatDoesNotSayItEncryptedTheFileIsRefusedAndTheFileRemoved() throws IOException {
        assertRefusedAndRemoved(Map.of());
    }

    @Test
    void aStoreThatSaysItEncryptedWithAnotherAlgorithmIsRefusedAndTheFileRemoved() throws IOException {
        assertRefusedAndRemoved(Map.of("x-amz-server-side-encryption", "AES256", "x-amz-server-side-encryption-aws-kms-key-id",
                "arn:aws:kms:us-east-1:000000000000:key/some-key"));
    }

    @Test
    void aStoreThatSaysKmsButNamesNoKeyIsRefusedAndTheFileRemoved() throws IOException {
        assertRefusedAndRemoved(Map.of("x-amz-server-side-encryption", "aws:kms"));
    }

    @Test
    void aStoreThatSaysKmsButNamesABlankKeyIsRefusedAndTheFileRemoved() throws IOException {
        assertRefusedAndRemoved(Map.of("x-amz-server-side-encryption", "aws:kms", "x-amz-server-side-encryption-aws-kms-key-id", " "));
    }

    /** A store that answers every write with the given headers: the write is refused and the object removed again. */
    private void assertRefusedAndRemoved(Map<String, String> answerHeaders) throws IOException {
        List<String> calls = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            calls.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            answerHeaders.forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
            exchange.sendResponseHeaders(exchange.getRequestMethod().equals("DELETE") ? 204 : 200, -1);
            exchange.close();
        });
        server.start();
        try {
            var store = store(at("http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(5), 1));
            String key = newKey();

            assertThatThrownBy(() -> store.put(Zone.CLEAN, key, new byte[] {1})).isInstanceOf(BoundaryFailureException.class)
                    .hasMessage("The file store did not encrypt the file with the key");

            assertThat(calls).containsExactly("PUT /" + CLEAN + "/" + key, "DELETE /" + CLEAN + "/" + key);
        } finally {
            server.stop(0);
        }
    }

    private static ServerSideEncryption defaultAlgorithm(String bucket) {
        try {
            return inspector.getBucketEncryption(GetBucketEncryptionRequest.builder().bucket(bucket).build())
                    .serverSideEncryptionConfiguration().rules().get(0).applyServerSideEncryptionByDefault().sseAlgorithm();
        } catch (S3Exception e) {
            return null;
        }
    }

    @Test
    void aStoreThatNeverAnswersHoldsACallNoLongerThanTheApiCallTimeout() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.start();
        try {
            var properties = new AwsProperties("http://127.0.0.1:" + server.getAddress().getPort(), "us-east-1", "test", "test",
                    QUARANTINE, CLEAN, EXPORT, ALIAS, Duration.ofSeconds(3), Duration.ofSeconds(20), Duration.ofSeconds(1), 3);
            var store = store(properties);
            long started = System.nanoTime();

            assertThatThrownBy(() -> store.get(Zone.CLEAN, newKey())).isInstanceOf(BoundaryFailureException.class);

            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aCallIsTriedNoMoreOftenThanMaxAttempts() throws IOException {
        List<String> calls = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            calls.add(exchange.getRequestMethod());
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        try {
            var store = store(at("http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(5), 2));

            assertThatThrownBy(() -> store.get(Zone.CLEAN, newKey())).isInstanceOf(BoundaryFailureException.class);

            assertThat(calls).hasSize(2);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void theSettingsPrintNoSecret() {
        var properties = new AwsProperties("http://localhost:4566", "us-east-1", "AKIA-VISIBLE-ID", "the-secret-value", QUARANTINE,
                CLEAN, EXPORT, ALIAS, Duration.ofSeconds(3), Duration.ofSeconds(30), Duration.ofSeconds(60), 2);

        assertThat(properties.toString()).doesNotContain("the-secret-value").doesNotContain("AKIA-VISIBLE-ID")
                .contains(QUARANTINE, ALIAS);
    }

    @Test
    void aStoreWithNoFixedKeysUsesAndClosesItsOwnCredentialChain() {
        var store = store(new AwsProperties(localstack.getEndpoint().toString(), localstack.getRegion(), null, null, QUARANTINE,
                CLEAN, EXPORT, ALIAS, Duration.ofSeconds(3), Duration.ofSeconds(30), Duration.ofSeconds(60), 2));

        assertThatCode(store::destroy).doesNotThrowAnyException();
    }

    private static int expiryDays(String bucket) {
        return inspector.getBucketLifecycleConfiguration(GetBucketLifecycleConfigurationRequest.builder().bucket(bucket).build())
                .rules().get(0).expiration().days();
    }

    private static String describeKeyId() throws Exception {
        ExecResult result = localstack.execInContainer("awslocal", "kms", "describe-key", "--key-id", ALIAS, "--query",
                "KeyMetadata.KeyId", "--output", "text");
        assertThat(result.getExitCode()).isZero();
        return result.getStdout().trim();
    }
}
