package sg.annualva.adapters.s3;

import java.net.URI;
import java.util.regex.Pattern;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import sg.annualva.boundary.BoundaryFailureException;
import sg.annualva.boundary.FileStore;
import sg.annualva.config.NotDevLocal;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

/**
 * The file store on S3 (ticket 51, ADR 0059): one bucket per zone, every write asking for SSE-KMS with the configured
 * customer-managed key and refused, and the object removed again, when the answer does not say it was encrypted that way.
 * A key is the application's random id; one that is not is refused before any call. Nothing is logged and every failure is
 * the same sentence, with no cause: what S3 says may quote a key or an endpoint (lm-19, as-13), and a file's bytes never
 * appear anywhere but in the store. The buckets, the key and the lifecycle rules are made outside the application
 * ({@code scripts/localstack-init} for LocalStack); the buckets must not be versioned, or a delete would leave the bytes.
 */
@Component
@NotDevLocal
@ConditionalOnProperty(prefix = "annualva.aws", name = "enabled", havingValue = "true")
class S3FileStore implements FileStore, DisposableBean {

    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private final S3Client s3;
    private final AwsProperties properties;

    S3FileStore(AwsProperties properties) {
        this.properties = properties;
        this.s3 = client(properties);
    }

    @Override
    public void put(Zone zone, String key, byte[] content) {
        String bucket = bucket(zone);
        String checked = checked(key);
        PutObjectRequest request = PutObjectRequest.builder().bucket(bucket).key(checked)
                .serverSideEncryption(ServerSideEncryption.AWS_KMS).ssekmsKeyId(properties.kmsKeyAlias())
                .bucketKeyEnabled(true).contentLength((long) content.length).build();
        ServerSideEncryption encryption;
        try {
            encryption = s3.putObject(request, RequestBody.fromBytes(content)).serverSideEncryption();
        } catch (SdkException e) {
            throw new BoundaryFailureException("The file store could not write the file");
        }
        if (encryption != ServerSideEncryption.AWS_KMS) {
            discard(bucket, checked);
            throw new BoundaryFailureException("The file store did not encrypt the file with the key");
        }
    }

    @Override
    public byte[] get(Zone zone, String key) {
        try {
            return s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket(zone)).key(checked(key)).build())
                    .asByteArray();
        } catch (NoSuchKeyException e) {
            throw new BoundaryFailureException("The file store holds nothing under this key");
        } catch (SdkException e) {
            throw new BoundaryFailureException("The file store could not read the file");
        }
    }

    @Override
    public void delete(Zone zone, String key) {
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket(zone)).key(checked(key)).build());
        } catch (SdkException e) {
            throw new BoundaryFailureException("The file store could not delete the file");
        }
    }

    @Override
    public void destroy() {
        s3.close();
    }

    private String bucket(Zone zone) {
        return switch (zone) {
            case QUARANTINE -> properties.quarantineBucket();
            case CLEAN -> properties.cleanBucket();
            case EXPORT -> properties.exportBucket();
        };
    }

    private static String checked(String key) {
        if (!KEY.matcher(key).matches()) {
            throw new BoundaryFailureException("The file store does not accept this key");
        }
        return key;
    }

    /** Removes an object that was written without the encryption asked for; a failure here changes nothing for the caller. */
    private void discard(String bucket, String key) {
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (SdkException ignored) {
            // The write is refused either way; the bucket's lifecycle expiry is the second line for what stays.
        }
    }

    private static S3Client client(AwsProperties properties) {
        var http = ApacheHttpClient.builder().connectionTimeout(properties.connectTimeout())
                .socketTimeout(properties.readTimeout());
        var builder = S3Client.builder().region(Region.of(properties.region())).credentialsProvider(credentials(properties))
                .httpClientBuilder(http);
        String endpoint = properties.endpoint();
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint)).forcePathStyle(true);
        }
        return builder.build();
    }

    private static AwsCredentialsProvider credentials(AwsProperties properties) {
        String id = properties.accessKeyId();
        String secret = properties.secretAccessKey();
        if (id != null && !id.isBlank() && secret != null && !secret.isBlank()) {
            return StaticCredentialsProvider.create(AwsBasicCredentials.create(id, secret));
        }
        return DefaultCredentialsProvider.builder().build();
    }
}
