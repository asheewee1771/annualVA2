# 51: Production adapters: S3 with SSE-KMS, SFS scanner, MCNS mail, MCC service client

**Spec:** [Annual VA spec](../spec.md), stories none (adapters).
**Release:** 1
**What to build:** Under the `dev-mcc` profile the app stores files in LocalStack S3 with SSE-KMS, scans through mock-SFS, sends mail through mock-MCNS and authenticates to them with the MCC service client, so the real integrations run daily on the developer machine.
**Blocked by:** 13 (Scan Report upload pipeline: quarantine, scan, check, store), 37 (Email through the mail relay with the content rules), 01 (Obtain the mock-m-sso stack and prepare the developer machine)
**Status:** ready-for-agent

- [ ] The file store adapter writes to the configured endpoint and buckets with SSE-KMS asserted on every write; the MCC client obtains the service token (`svc-appfw`, `private_key_jwt` ES512) and adds the bearer and `x-application-id` to every outbound call.
- [ ] The scanner adapter follows the SFS flow: `POST /put`, the presigned upload, `POST /get` polling until Allowed or Blocked, then the clean download with hash check (`Unchanged` must match the upload hash, `Sanitized` is recorded with the clean hash, `Blocked` rejects); the mail adapter maps template ids, enforces the value allow-list and applies a rate limiter and retry.
- [ ] Adapters live only in the adapters package (architecture test), are absent under the `ci` profile, and a platform-tagged test suite exercises them against the running mock stack on demand; the README run book rows for scan verdicts and mail logs are verified by hand and the results noted under Comments.
- [ ] The platform-tagged suite passes against the mock stack, and the Seam 1 suite and both gates stay green.

## Comments
