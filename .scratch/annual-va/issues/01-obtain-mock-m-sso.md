# 01: Obtain the mock-m-sso stack and prepare the developer machine

**Spec:** [Annual VA spec](../spec.md), stories none (prerequisite).
**Release:** 1
**What to build:** A developer can run `docker compose up -d` and reach Keycloak, MockPass, mock-SFS, mock-MCNS and LocalStack, so that sign-in, scanning and mail can be demonstrated locally. The repository and standards hold no URL for `mock-m-sso`; a human must obtain it.
**Blocked by:** None (can start immediately)
**Status:** ready-for-human

- [ ] The `mock-m-sso` repository is cloned to `D:\projects\mock-m-sso` (a sibling of this repo) and contains `keycloak/{data,providers,certs}`, `mock-mpds/{src,pom.xml,.m2,payload}`, `mock-mcns` and `mock-sfs`.
- [ ] `docker compose up -d` from the repo root brings every service to its documented health state (README, Services and ports).
- [ ] In Keycloak (`https://localhost:9080`, realm SSO) the `app-appfw` client allows the redirect `https://localhost:5173/login/oauth2/code/*` and fetches the JWKS from `https://host.internal:8443/.well-known/jwks.json`; any realm change needed is recorded as a follow-up to the mock-m-sso owners.
- [ ] If the machine sits behind a corporate TLS proxy, `compose.override.yml` is created from the example and the three maven containers start.
- [ ] The outcome (source of the repo, access steps, realm findings) is appended under Comments so the next developer can repeat it.

## Comments
