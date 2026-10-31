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

### Note from ticket 08 (2026-10-06): scanner adapter also serves the Register upload

The SFS scanner adapter built here is also what ticket 13 will put in front of the System Register workbook upload (see the note on ticket 13). Nothing extra is needed in the adapter; check that the platform-tagged suite scans a small `.xlsx` as well as a report.

### Note from ticket 13 (2026-10-13): replace the fail-closed stand-ins and map Sanitized

- Ticket 13 added the `FileStore` and `MalwareScanner` ports (`boundary`) and fail-closed stand-ins, `adapters.pending.UnwiredFileStore` and `UnwiredMalwareScanner`, so the application starts under `dev-mcc` and in production before the real adapters exist. This ticket must **remove or replace** them: with both present, a real adapter and a stand-in are two beans of one type (`NoUniqueBeanDefinitionException`). Delete the `adapters.pending` package when the S3 and SFS adapters land.
- The scanner's **Sanitized** verdict must map to `MalwareScanner.Verdict.CLEAN_SANITIZED`: the application rejects such a file (Scan Report and Register workbook) and never fetches the altered copy (docs/agents/reviewer-decisions.md, ADR 0021 decision 5). `fetchClean` is called only for `CLEAN_UNCHANGED`, and its bytes must be the uploaded ones.
- Adapters should throw `BoundaryFailureException`, but any runtime exception from a port call is treated as a failed attempt, so none can leave a file stuck.
- The quarantine bucket should also carry a lifecycle expiry as a second line behind the application's own delete-after-commit (ADR 0021 decision 6); the clean copy of a Scan Report file is keyed by the file's own id.

### Note from ticket 37 (2026-10-25)

The mail adapter implements `sg.annualva.boundary.MailRelay`: the template-id mapping and the value allow-list already run on the application side of the port (ADR 0046), so the adapter only speaks to MCNS. Its connect and read timeouts must stay well below the outbox lease (`annualva.mail.lease`, five minutes): a call that outlasts the lease lets another pass send the mail again, and a last attempt that does is recorded `FAILED` although MCNS took it. Answer `AddressRejected` for an address MCNS refuses (ticket 39), throw `BoundaryFailureException` for anything that may pass, and never log the recipient.

### Note from ticket 47 (2026-10-25): the identity provider's account status

Ticket 47 (ADR 0056) added the `sg.annualva.boundary.IdentityStatus` port and its stand-in `adapters.pending.UnwiredIdentityStatus`, which always throws, so the session check (IM8 ac-3) leaves every session alone in production until this ticket replaces it. The adapter belongs here because it needs the MCC service client:

- implement `IdentityStatus.disabled(ssoId)` against MCC SSO's account status (what MCC SSO offers is part of the H4 onboarding), with connect and read timeouts of a few seconds, since it runs inside a request;
- throw `BoundaryFailureException` for anything that is not a clear answer, and never log the `afcas.uuid`;
- remove `UnwiredIdentityStatus` only together with this adapter: deleting the `adapters.pending` package without it leaves no `IdentityStatus` bean and the application does not start;
- check against H4 that the sign-in token's `afcas.status` claim (provisional in ADR 0056 item 8) is what MCC SSO sends, or change `MccIdentityProvider` to what it does send.

### Scope decision (2026-10-29)

Ticket 01 (`mock-m-sso`) is still open, so the scanner, mail and service-client adapters cannot be checked against the mocks. The user decided to build **only the file store** now: the S3 store with SSE-KMS against LocalStack, replacing `UnwiredFileStore` under `dev-mcc`, with its test against the running LocalStack. It starts after ticket 63 reports, so the two do not share Docker ports. The scanner, mail, MCC service client and the account-status (`IdentityStatus`) adapter stay `adapters.pending` stubs (`UnwiredIdentityStatus` is removed only together with its real adapter), and every box of this ticket except the file store's remains open until ticket 01 is done. Status stays `ready-for-agent`.

### Progress note (2026-10-31): the file store is built (ADR 0059)

`adapters.s3.S3FileStore` (S3 with SSE-KMS, one bucket per zone, the write refused and the object removed unless S3 answers `aws:kms`) replaces `UnwiredFileStore` wherever `annualva.aws.enabled` is true (`dev-mcc`); it never loads under `dev-local` or `ci`. `S3FileStoreTest` runs it against LocalStack in Testcontainers with the repository's init scripts, which now also make the export bucket and the lifecycle rules. This satisfies the file-store half of box 1 (the MCC client half is open) and the file-store part of box 3's "only in the adapters package, absent under `ci`" (`ModuleConventionsArchTest`, `S3FileStoreAbsentTest`); no box is ticked because each also names the scanner, mail or MCC client. The `adapters.pending` package stays for the scanner, mail relay and `IdentityStatus` stand-ins.
