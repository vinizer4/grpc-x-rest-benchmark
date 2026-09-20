#!/usr/bin/env bash
# Samples `docker stats` for the backend service containers once per second
# and writes the series to a CSV, so CPU/memory under load can be compared
# between the REST and gRPC services for a given benchmark run.
#
# Usage: monitor.sh <output.csv> <duration_seconds> [container...]
set -euo pipefail

OUT_FILE="${1:?Usage: monitor.sh <output.csv> <duration_seconds> [container...]}"
DURATION_SECONDS="${2:?Usage: monitor.sh <output.csv> <duration_seconds> [container...]}"
shift 2

if [ "$#" -gt 0 ]; then
  CONTAINERS=("$@")
else
  CONTAINERS=(grpc-x-rest-benchmark-sales-service-rest-1 grpc-x-rest-benchmark-sales-service-grpc-1)
fi

echo "timestamp,container,cpu_perc,mem_usage,mem_perc,net_io,block_io" > "$OUT_FILE"

END=$((SECONDS + DURATION_SECONDS))
while [ "$SECONDS" -lt "$END" ]; do
  TS="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  for container in "${CONTAINERS[@]}"; do
    STATS="$(docker stats --no-stream --format '{{.CPUPerc}},{{.MemUsage}},{{.MemPerc}},{{.NetIO}},{{.BlockIO}}' "$container" 2>/dev/null || echo ",,,,")"
    echo "$TS,$container,$STATS" >> "$OUT_FILE"
  done
  sleep 1
done

echo "Resource usage written to $OUT_FILE"
