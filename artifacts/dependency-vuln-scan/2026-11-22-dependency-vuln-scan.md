# Dependency vulnerability scan

- Scan timestamp: 2026-11-22T04:14:04Z
- Project: Annual VA (annualVA2), `frontend/` and `backend/`
- Scanners: `npm audit --json` (`frontend/package-lock.json`); OWASP Dependency-Check for `backend/pom.xml` did NOT complete (see Scanner notes)
- Point-in-time: re-run `/dependency-vuln-scan` to refresh.

## Summary

| Severity | Count |
|---|---|
| Critical | 0 |
| High | 1 |
| Medium | 4 |
| Low | 3 |
| Info | 0 |

A count is one advisory against one package. The counts cover the front end only: the back end was not scanned.

## Findings

### High

| ID | Package | Installed | Fixed | Description | Scanner |
|---|---|---|---|---|---|
| GHSA-587h-7wmj-h5pm | vite | 8.3.2 (affected: >=8.3.0 <=8.3.2) | npm audit fix | launch-editor: `fs.existsSync()` existence check can be bypassed on Windows | npm audit (development dependency) |

### Medium

| ID | Package | Installed | Fixed | Description | Scanner |
|---|---|---|---|---|---|
| GHSA-698v-4r3x-3vcv | vite | 8.3.2 (affected: >=8.3.0 <=8.3.2) | npm audit fix | launch-editor: escapeCmdArgs() omits % escaping on Windows | npm audit (development dependency) |
| GHSA-r825-w8mm-5pxw | vite | 8.3.2 (affected: >=8.3.0 <=8.3.2) | npm audit fix | launch-editor: Editor CLI flag misinterpretation causing arbitrary command execution for some editors | npm audit (development dependency) |
| GHSA-rq7h-c2jc-7f22 | vite | 8.3.2 (affected: >=8.3.0 <=8.3.2) | npm audit fix | Vite: Files outside `server.fs.allow` could be served when project paths match system paths on non-Windows machine | npm audit (development dependency) |
| GHSA-vfpm-58rq-9qcg | vite | 8.3.2 (affected: >=8.3.0 <=8.3.2) | npm audit fix | Vite: First 4 bytes of files outside of `server.fs` setting is exposed by `?vite-wasm-instance` | npm audit (development dependency) |

### Low

| ID | Package | Installed | Fixed | Description | Scanner |
|---|---|---|---|---|---|
| GHSA-h4fr-cmh5-qr62 | fallow | 3.31.0 (affected: <3.34.0) | npm audit fix | Fallow: A `.*` glob segment let an analyzed repository make Fallow test paths in the parent of the project root | npm audit (development dependency) |
| GHSA-jfwm-3p3c-6hx5 | fallow | 3.31.0 (affected: >=2.68.0 <3.34.0) | npm audit fix | Fallow: Jest and Vitest `projects` entries let an analyzed repository make Fallow read files outside the project root | npm audit (development dependency) |
| GHSA-9jrq-w75r-8gcw | vite | 8.3.2 (affected: >=8.3.0 <=8.3.2) | npm audit fix | Vite: Cross origin script execution through Vite custom middleware | npm audit (development dependency) |

## Remediation guidance

- `vite` (front end, development only): `cd frontend && npm audit fix`, then `npm run check`.

## Scanner notes

- **`npm audit`** ran in `frontend/` after `npm ci` and exited 1, which is expected when it finds anything. Every finding is in a development dependency (`vite`, `fallow`); the production bundle holds `react`, `react-dom` and the application only.
- **OWASP Dependency-Check (Maven) did NOT complete: the back end has no vulnerability result.** The NVD data never finished downloading, so the `check` goal never ran and there is no `dependency-check-report.json`. No NVD API key or other credential was used. What happened, in UTC on 2026-11-22:
  1. 03:09 `./mvnw org.owasp:dependency-check-maven:update-only` (the plugin resolved to 13.0.0): exit 1 after 29 seconds. The NVD API answered `404`, "Invalid apiKey."; with no key configured this plugin version sends an empty one.
  2. 03:12 the same goal pinned to 12.2.2, which runs without a key (5 requests in 30 seconds): 370,000 of 430,425 NVD records (86 %) in 20 minutes 40 seconds, then exit 1, "There is not enough space on the disk". The default data directory is under `~/.m2` on drive C:, which had less than 1 GB free. The partial database (734 MB) was removed.
  3. 03:34 again with `-DdataDirectory` on drive D:; stopped by hand at 03:40 (14 %) because the plugin's temporary files were still written to drive C:.
  4. 03:40 again with `-DdataDirectory` and `-Djava.io.tmpdir` on drive D:; 330,000 of 430,425 records (77 %) with 55 "NVD API request failures are occurring; retrying" warnings, almost no progress after 04:01. Stopped at 04:11, 61 minutes after the first attempt (the limit set for this try was about 60 minutes). The partial data was removed.
- **Fallback (dependency listing only):** `./mvnw -o dependency:tree -Dscope=runtime` succeeded: 15 direct dependencies and 157 entries in the runtime tree. A listing says nothing about vulnerabilities; the direct dependencies and their versions are in section 5 of `docs/tech-architecture.md`.
- **To complete the back-end scan:** give the plugin an NVD API key from the environment (`-DnvdApiKey=...`, never committed; with a key the download takes minutes) or point it at an internal NVD mirror (`-DnvdDatafeedUrl=...`), keep its data where there is at least 2 GB free (`-DdataDirectory=<folder on D:>` and `MAVEN_OPTS=-Djava.io.tmpdir=<folder on D:>`), then run `cd backend && ./mvnw org.owasp:dependency-check-maven:12.2.2:check -DfailBuildOnCVSS=0 -Dformat=JSON` and read `backend/target/dependency-check-report.json`. Version 13.0.0 of the plugin needs the key.
