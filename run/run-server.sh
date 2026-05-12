#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR_PATH="$SCRIPT_DIR/../quickfix-server/build/libs/quickfix-server-all.jar"

if [[ ! -f "$JAR_PATH" ]]; then
  echo "Missing server JAR at: $JAR_PATH"
  echo "Build it first with: ./gradlew shadowJar"
  exit 1
fi

cd "$SCRIPT_DIR"

echo "Cleaning up previous run artifacts..."
rm -f server-errors.log server-debug.log

exec java "-Dlogback.configurationFile=$SCRIPT_DIR/logback-server.xml" -jar "$JAR_PATH"
