# The MCC mock stack (mock-m-sso) is the local platform; Seam 1 fakes are the test boundary

Status: accepted

Developers run the real integrations daily against the org's mock stack: Keycloak brokering MockPass (MCC SSO), mock-SFS (malware scanning), mock-MCNS (email) and LocalStack (S3 and KMS), plus a compose PostgreSQL. Tests do not: Seam 1 fakes the five boundary adapters in process so the suite is fast and deterministic. The compose service blocks are copied verbatim from the org bootstrap standard because they are compatibility pins matched to the volume-mounted mock source.

Consequences: two implementations per boundary port (production adapter and harness fake); the `dev-mcc` profile may never be active in a deployed environment; `mock-m-sso` has no recorded URL and must be obtained by a human (a ready-for-human ticket); no interim OIDC stand-in is built unless that slips past the sign-in slice, and if one is added it is removed by the integration ticket.
