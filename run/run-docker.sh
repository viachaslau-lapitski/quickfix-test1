#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$SCRIPT_DIR/.."
COMPOSE_FILE="$REPO_ROOT/docker-compose.yml"

# Network / latency constraints
LAT_MS="${LAT_MS:-25}"           # one-way delay (ms) applied on each container's egress
LAT_JITTER_MS="${LAT_JITTER_MS:-0}"
PLR="${PLR:-0.1}"                # packet-loss rate (%)
BW_MBIT="${BW_MBIT:-10}"         # egress bandwidth cap (Mbit/s); 0 = unlimited

# Resource constraints
SERVER_CPUS="${SERVER_CPUS:-1.0}"
SERVER_MEM="${SERVER_MEM:-512m}"
CLIENT_CPUS="${CLIENT_CPUS:-2.0}"
CLIENT_MEM="${CLIENT_MEM:-256m}"

# JVM flags (applied to both containers)
JVM_OPTS="${JVM_OPTS:-}"

export LAT_MS LAT_JITTER_MS PLR BW_MBIT SERVER_CPUS SERVER_MEM CLIENT_CPUS CLIENT_MEM JVM_OPTS

echo "=== FIX benchmark — Docker ==="
echo "  latency : ${LAT_MS}ms ± ${LAT_JITTER_MS}ms  loss=${PLR}%  bw=${BW_MBIT}Mbit/s"
echo "  server  : cpus=${SERVER_CPUS}  mem=${SERVER_MEM}"
echo "  client  : cpus=${CLIENT_CPUS}  mem=${CLIENT_MEM}"
echo ""

exec docker compose -f "$COMPOSE_FILE" up "$@"
