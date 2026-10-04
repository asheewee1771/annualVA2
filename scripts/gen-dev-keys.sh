#!/usr/bin/env bash
# Generates the local development key material. Run once from the repo root in Git Bash:
#   bash scripts/gen-dev-keys.sh
# Produces
#   backend/src/main/resources/dev-keystore.p12   self-signed EC P-521 cert for HTTPS :8443 (committed, mock)
#   $TMPDIR/annualva-mcc-dev-ec521*.pem            private_key_jwt signing key (NOT committed; *.pem is gitignored)
# and prints the base64 strings to paste into application-dev-mcc.yml (mock values, safe to commit).
# Keycloak fetches the JWKS from https://host.internal:8443/.well-known/jwks.json, so a fresh key works.
set -euo pipefail
cd "$(dirname "$0")/.."
KEYTOOL="${JAVA_HOME:+$JAVA_HOME/bin/}keytool"
STORE=backend/src/main/resources/dev-keystore.p12
OUT="${TMPDIR:-/tmp}"
if [ ! -f "$STORE" ]; then
  "$KEYTOOL" -genkeypair -alias mcc-dev -keyalg EC -groupname secp521r1 -sigalg SHA512withECDSA \
    -keystore "$STORE" -storetype PKCS12 -storepass changeit -validity 3650 \
    -dname "CN=localhost" -ext "SAN=dns:localhost,dns:host.internal,ip:127.0.0.1"
  echo "Created $STORE"
else
  echo "$STORE already exists, leaving it alone"
fi
openssl ecparam -genkey -name secp521r1 -noout | openssl pkcs8 -topk8 -nocrypt -out "$OUT/annualva-mcc-dev-ec521.pem"
openssl pkey -in "$OUT/annualva-mcc-dev-ec521.pem" -pubout -out "$OUT/annualva-mcc-dev-ec521-pub.pem"
echo
echo "annualva.mcc.signing-key.private (base64 PKCS#8):"
base64 -w0 "$OUT/annualva-mcc-dev-ec521.pem"; echo
echo
echo "annualva.mcc.signing-key.public (base64 SPKI):"
base64 -w0 "$OUT/annualva-mcc-dev-ec521-pub.pem"; echo
echo
echo "PEM files are in $OUT (gitignored). Paste the base64 values into application-dev-mcc.yml."
