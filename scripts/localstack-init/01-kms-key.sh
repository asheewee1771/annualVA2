#!/usr/bin/env bash
# Runs inside LocalStack once it is healthy. Creates the dev customer-managed key used for
# SSE-KMS on the file buckets. Idempotent: skips when the alias already exists.
set -euo pipefail
ALIAS="alias/annualva-dev"
if awslocal kms list-aliases --query "Aliases[?AliasName=='${ALIAS}'].AliasName" --output text | grep -q "${ALIAS}"; then
  echo "KMS alias ${ALIAS} already exists"
  exit 0
fi
KEY_ID=$(awslocal kms create-key --description "Annual VA dev file-store key" --query KeyMetadata.KeyId --output text)
awslocal kms create-alias --alias-name "${ALIAS}" --target-key-id "${KEY_ID}"
awslocal kms enable-key-rotation --key-id "${KEY_ID}"
echo "Created KMS key ${KEY_ID} as ${ALIAS}"
