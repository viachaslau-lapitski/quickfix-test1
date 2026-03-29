#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR_PATH="$SCRIPT_DIR/../quickfix-client/build/libs/quickfix-client-all.jar"
TIMEOUT_SECONDS=65
RUN_COUNT=1
PAUSE_SECONDS=5

if [[ ! -f "$JAR_PATH" ]]; then
  echo "Missing client JAR at: $JAR_PATH"
  echo "Build it first with: ./gradlew shadowJar"
  exit 1
fi

ARGS=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --timeout=*)
      TIMEOUT_SECONDS="${1#*=}"
      shift
      ;;
    --timeout)
      TIMEOUT_SECONDS="${2:-}"
      shift 2
      ;;
    --runs=*)
      RUN_COUNT="${1#*=}"
      shift
      ;;
    --runs|--run-count)
      RUN_COUNT="${2:-}"
      shift 2
      ;;
    --pause=*)
      PAUSE_SECONDS="${1#*=}"
      shift
      ;;
    --pause)
      PAUSE_SECONDS="${2:-}"
      shift 2
      ;;
    *)
      ARGS+=("$1")
      shift
      ;;
  esac
done

if [[ -n "$TIMEOUT_SECONDS" && "$TIMEOUT_SECONDS" != 0 ]]; then
  if ! [[ "$TIMEOUT_SECONDS" =~ ^[0-9]+$ ]]; then
    echo "Invalid --timeout value: $TIMEOUT_SECONDS (expected seconds)"
    exit 1
  fi
fi
if ! [[ "$RUN_COUNT" =~ ^[0-9]+$ ]]; then
  echo "Invalid --runs value: $RUN_COUNT (expected whole number)"
  exit 1
fi
if ! [[ "$PAUSE_SECONDS" =~ ^[0-9]+$ ]]; then
  echo "Invalid --pause value: $PAUSE_SECONDS (expected seconds)"
  exit 1
fi

cd "$SCRIPT_DIR"

TIMEOUT_BIN=""
if [[ "$TIMEOUT_SECONDS" != 0 ]]; then
  if command -v gtimeout >/dev/null 2>&1; then
    TIMEOUT_BIN="gtimeout"
  elif command -v timeout >/dev/null 2>&1; then
    TIMEOUT_BIN="timeout"
  fi

  if [[ -z "$TIMEOUT_BIN" ]]; then
    echo "Timeout requested but 'gtimeout' or 'timeout' not found."
    echo "On macOS, install coreutils (gtimeout) or run without --timeout."
    exit 1
  fi
fi

BASE_CMD=(java -jar "$JAR_PATH")
if [[ ${#ARGS[@]} -gt 0 ]]; then
  BASE_CMD+=("${ARGS[@]}")
fi

for ((run=1; run<=RUN_COUNT; run++)); do
  if [[ "$TIMEOUT_SECONDS" != 0 ]]; then
    set +e
    "$TIMEOUT_BIN" "$TIMEOUT_SECONDS" "${BASE_CMD[@]}"
    exit_code=$?
    set -e
    if [[ $exit_code -ne 0 && $exit_code -ne 124 && $exit_code -ne 137 ]]; then
      exit $exit_code
    fi
  else
    "${BASE_CMD[@]}"
  fi

  if [[ $run -lt $RUN_COUNT ]]; then
    sleep "$PAUSE_SECONDS"
  fi
done
