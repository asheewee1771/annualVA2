/**
 * The production file store (ticket 51, ADR 0059): S3 with SSE-KMS, three buckets, one per zone. It exists only where
 * {@code annualva.aws.enabled} is true ({@code dev-mcc}, against LocalStack, today) and never under {@code dev-local};
 * where it is off, the fail-closed {@code adapters.pending} store stays. Depends on boundary and on {@code config.NotDevLocal}, the profile marker; the AWS SDK is used
 * nowhere else (an architecture test).
 */
package sg.annualva.adapters.s3;
