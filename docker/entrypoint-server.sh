#!/bin/sh
set -e

# Apply tc netem on eth0 to simulate one-way network conditions (server → client path).
# Set LAT_MS, LAT_JITTER_MS, PLR, BW_MBIT via environment variables.
apply_netem() {
  LAT="${LAT_MS:-0}"
  JITTER="${LAT_JITTER_MS:-0}"
  LOSS="${PLR:-0}"
  BW="${BW_MBIT:-0}"

  if [ "$LAT" -le 0 ] && [ "$JITTER" -le 0 ] && [ "$LOSS" = "0" ] && [ "$BW" -le 0 ]; then
    return
  fi

  OPTS="delay ${LAT}ms ${JITTER}ms"
  [ "$LOSS" != "0" ] && OPTS="$OPTS loss ${LOSS}%"
  [ "$BW" -gt 0 ]    && OPTS="$OPTS rate ${BW}mbit"

  echo "[net] Applying to eth0: netem $OPTS"
  tc qdisc add dev eth0 root netem $OPTS
}

apply_netem

# shellcheck disable=SC2086
exec java ${JVM_OPTS:-} -Dlogback.configurationFile=/app/logback-server.xml -jar /app/server.jar
