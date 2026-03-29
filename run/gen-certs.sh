#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CERTS_DIR="$SCRIPT_DIR/certs"
PASS="changeit"

echo "==> Recreating $CERTS_DIR"
rm -rf "$CERTS_DIR"
mkdir -p "$CERTS_DIR"

echo "==> Generating server keypair -> server.keystore"
keytool -genkeypair \
  -alias server \
  -keyalg RSA -keysize 2048 \
  -validity 3650 \
  -dname "CN=server,O=perf,C=US" \
  -keystore "$CERTS_DIR/server.keystore" \
  -storetype JKS \
  -storepass "$PASS" \
  -keypass "$PASS"

echo "==> Generating client keypair -> client.keystore"
keytool -genkeypair \
  -alias client \
  -keyalg RSA -keysize 2048 \
  -validity 3650 \
  -dname "CN=client,O=perf,C=US" \
  -keystore "$CERTS_DIR/client.keystore" \
  -storetype JKS \
  -storepass "$PASS" \
  -keypass "$PASS"

echo "==> Exporting server certificate -> server.cer"
keytool -exportcert \
  -alias server \
  -keystore "$CERTS_DIR/server.keystore" \
  -storepass "$PASS" \
  -file "$CERTS_DIR/server.cer"

echo "==> Exporting client certificate -> client.cer"
keytool -exportcert \
  -alias client \
  -keystore "$CERTS_DIR/client.keystore" \
  -storepass "$PASS" \
  -file "$CERTS_DIR/client.cer"

echo "==> Importing server.cer into client.truststore"
keytool -importcert \
  -noprompt -trustcacerts \
  -alias server \
  -file "$CERTS_DIR/server.cer" \
  -keystore "$CERTS_DIR/client.truststore" \
  -storetype JKS \
  -storepass "$PASS"

echo "==> Importing client.cer into server.truststore"
keytool -importcert \
  -noprompt -trustcacerts \
  -alias client \
  -file "$CERTS_DIR/client.cer" \
  -keystore "$CERTS_DIR/server.truststore" \
  -storetype JKS \
  -storepass "$PASS"

echo "==> Removing temp .cer files"
rm "$CERTS_DIR/server.cer" "$CERTS_DIR/client.cer"

echo ""
echo "Done. Files in $CERTS_DIR:"
for f in server.keystore client.keystore server.truststore client.truststore; do
  echo "  $f"
done
