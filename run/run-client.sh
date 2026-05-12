#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR_PATH="$SCRIPT_DIR/../quickfix-client/build/libs/quickfix-client-all.jar"
TIMEOUT_SECONDS=65
RUN_COUNT=1
PAUSE_SECONDS=5
STOP_REQUESTED=0
SSL_MODE=0

on_stop_signal() {
  STOP_REQUESTED=1
}

trap on_stop_signal INT TERM TSTP

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
    --ssl)
      SSL_MODE=1
      shift
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

echo "Cleaning up previous run artifacts..."
rm -f errors.log debug.log
rm -rf logs

if [[ "$SSL_MODE" -eq 1 ]]; then
  if [[ ! -f "client-ssl.cfg" ]]; then
    echo "Missing client-ssl.cfg in $SCRIPT_DIR"
    exit 1
  fi
  echo "SSL mode: copying client-ssl.cfg -> client.cfg"
  cp client-ssl.cfg client.cfg
else
  if [[ ! -f "client-plain.cfg" ]]; then
    echo "Missing client-plain.cfg in $SCRIPT_DIR"
    exit 1
  fi
  echo "Plain mode: copying client-plain.cfg -> client.cfg"
  cp client-plain.cfg client.cfg
fi

TIMEOUT_BIN=""
TIMEOUT_FOREGROUND_ARGS=()
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

  # Keep the command in the foreground so terminal signals (Ctrl+C/Ctrl+Z)
  # are delivered as expected.
  if "$TIMEOUT_BIN" --help 2>&1 | grep -q -- '--foreground'; then
    TIMEOUT_FOREGROUND_ARGS=(--foreground)
  fi
fi

BASE_CMD=(java "-Dlogback.configurationFile=$SCRIPT_DIR/logback-client.xml" -jar "$JAR_PATH")
if [[ ${#ARGS[@]} -gt 0 ]]; then
  BASE_CMD+=("${ARGS[@]}")
fi

for ((run=1; run<=RUN_COUNT; run++)); do
  if [[ "$STOP_REQUESTED" -eq 1 ]]; then
    exit 130
  fi

  if [[ "$TIMEOUT_SECONDS" != 0 ]]; then
    set +e
    "$TIMEOUT_BIN" "${TIMEOUT_FOREGROUND_ARGS[@]}" "$TIMEOUT_SECONDS" "${BASE_CMD[@]}"
    exit_code=$?
    set -e

    if [[ "$STOP_REQUESTED" -eq 1 ]]; then
      exit 130
    fi

    if [[ $exit_code -eq 130 || $exit_code -eq 143 || $exit_code -eq 148 ]]; then
      exit "$exit_code"
    fi

    if [[ $exit_code -ne 0 && $exit_code -ne 124 && $exit_code -ne 137 ]]; then
      exit $exit_code
    fi
  else
    "${BASE_CMD[@]}"
  fi

  if [[ $run -lt $RUN_COUNT ]]; then
    if [[ "$STOP_REQUESTED" -eq 1 ]]; then
      exit 130
    fi
    sleep "$PAUSE_SECONDS"
  fi
done
