#!/usr/bin/env bash
# Runs inside LocalStack after 01-kms-key.sh. Creates the quarantine (dirty) and clean file
# buckets with SSE-KMS default encryption using the dev key, and blocks public access.
set -euo pipefail
ALIAS="alias/annualva-dev"
for BUCKET in annualva-quarantine-dev annualva-clean-dev; do
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
