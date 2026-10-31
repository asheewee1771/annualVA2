package sg.annualva.adapters.s3;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;
import sg.annualva.config.NotDevLocal;

/**
 * Settings of the S3 file store (prefix {@code annualva.aws}); bound only where {@code annualva.aws.enabled} is true.
 *
 * @param endpoint the S3 endpoint to use instead of AWS's own (LocalStack under {@code dev-mcc}); null for AWS; when
 *     set, buckets are addressed by path, since a local endpoint has no wildcard host name
 * @param region the AWS region
 * @param accessKeyId a fixed access key (mock values under {@code dev-mcc}); null to use the SDK's default credential chain
 * @param secretAccessKey the secret for {@code accessKeyId}
 * @param quarantineBucket holds uploads the scanner has not passed
 * @param cleanBucket holds what the scanner passed
 * @param exportBucket holds the files the application made for someone to download
 * @param kmsKeyAlias the customer-managed key every write asks for (SSE-KMS), by alias or key id
 * @param connectTimeout how long to wait to reach the store
 * @param readTimeout how long to wait for the store's answer; a file may be up to 50 MB
 */
@ConfigurationProperties("annualva.aws")
@Validated
@NotDevLocal
@ConditionalOnProperty(prefix = "annualva.aws", name = "enabled", havingValue = "true")
public record AwsProperties(
        @Nullable String endpoint,
        @NotBlank String region,
        @Nullable String accessKeyId,
        @Nullable String secretAccessKey,
        @NotBlank String quarantineBucket,
        @NotBlank String cleanBucket,
        @NotBlank String exportBucket,
        @NotBlank String kmsKeyAlias,
        @DefaultValue("3s") @NotNull Duration connectTimeout,
        @DefaultValue("30s") @NotNull Duration readTimeout) {}
