#!/usr/bin/env bash
# Loads scripts/dev-data/system-register.sample.xlsx into a running dev-local back end (ticket 63) the way a VA POC does:
# sign in as the VA POC 1 persona, upload the workbook, apply the preview. That is the only thing that gives the SMO and
# Cyber Manager personas their roles; nothing here grants a role directly. Needs curl; the back end must be running with
# -Dspring-boot.run.profiles=dev-local and the front end dev server (npm run dev: some Windows curl builds cannot complete
# the handshake with the back end's dev certificate, node can, so the default goes through the proxy). Run it again after a Register change to load the sample over it.
#
#   bash scripts/dev-data/load-register.sh            # through the front end dev server, https://localhost:5173
#   BASE=https://localhost:8443 bash scripts/dev-data/load-register.sh   # the back end directly
set -euo pipefail

BASE="${BASE:-https://localhost:5173}"
# pwd -W gives a Windows path where there is one (Git Bash), which a native curl can open; elsewhere it is plain pwd.
SAMPLE="$(cd "$(dirname "$0")" && { pwd -W 2>/dev/null || pwd; })/system-register.sample.xlsx"
VA_POC_1="11111111-0000-4000-8000-000000000005"
JAR="$(mktemp)"
trap 'rm -f "$JAR"' EXIT

call() { curl -sk -b "$JAR" -c "$JAR" "$@"; }
field() { sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p" | head -n 1; }

csrf_header=""; csrf_token=""
fetch_csrf() {
  local body
  body="$(call "$BASE/api/csrf")"
  csrf_header="$(printf '%s' "$body" | field headerName)"
  csrf_token="$(printf '%s' "$body" | field token)"
  [ -n "$csrf_token" ] || { echo "No CSRF token from $BASE: is the back end running with the dev-local profile?" >&2; exit 1; }
}

fetch_csrf
status="$(call -o /dev/null -w '%{http_code}' -X POST -H "$csrf_header: $csrf_token" -H 'Content-Type: application/json' \
  -d "{\"persona\":\"$VA_POC_1\"}" "$BASE/api/dev/sign-in")"
[ "$status" = "204" ] || { echo "Signing in as VA POC 1 answered $status (404 means the back end is not running dev-local)." >&2; exit 1; }
fetch_csrf

preview="$(call -X POST -H "$csrf_header: $csrf_token" -F "file=@$SAMPLE;type=application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" "$BASE/api/system-register/uploads")"
id="$(printf '%s' "$preview" | field id)"
[ -n "$id" ] || { echo "The upload was refused: $preview" >&2; exit 1; }

applied="$(call -X POST -H "$csrf_header: $csrf_token" "$BASE/api/system-register/previews/$id/apply")"
echo "Register applied: $applied"
call -o /dev/null -X POST -H "$csrf_header: $csrf_token" "$BASE/api/logout"
