#!/usr/bin/env bash
# Runs inside LocalStack after 01-kms-key.sh. Creates the quarantine (dirty), clean and export file
# buckets with SSE-KMS default encryption using the dev key, blocks public access and sets the lifecycle
# expiry that is the second line behind the application's own deletes (ADR 0021 decision 6, ADR 0045):
# quarantine one day (a scan takes minutes), export two days (the application removes an export after
# 24 hours; S3 expires in whole days). The clean bucket has none: the application disposes of its files.
set -euo pipefail
ALIAS="alias/annualva-dev"
for BUCKET in annualva-quarantine-dev annualva-clean-dev annualva-export-dev; do
  if awslocal s3api head-bucket --bucket "${BUCKET}" 2>/dev/null; then
    echo "Bucket ${BUCKET} already exists"
  else
    awslocal s3api create-bucket --bucket "${BUCKET}"
    echo "Created bucket ${BUCKET}"
  fi
  awslocal s3api put-public-access-block --bucket "${BUCKET}" \
    --public-access-block-configuration BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
  awslocal s3api put-bucket-encryption --bucket "${BUCKET}" --server-side-encryption-configuration \
    "{\"Rules\":[{\"ApplyServerSideEncryptionByDefault\":{\"SSEAlgorithm\":\"aws:kms\",\"KMSMasterKeyID\":\"${ALIAS}\"},\"BucketKeyEnabled\":true}]}"
done
expire_after_days() {
  awslocal s3api put-bucket-lifecycle-configuration --bucket "$1" --lifecycle-configuration \
    "{\"Rules\":[{\"ID\":\"expire-leftovers\",\"Status\":\"Enabled\",\"Filter\":{\"Prefix\":\"\"},\"Expiration\":{\"Days\":$2}}]}"
}
expire_after_days annualva-quarantine-dev 1
expire_after_days annualva-export-dev 2
