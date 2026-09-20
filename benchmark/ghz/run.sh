#!/usr/bin/env bash
# Runs a ghz load test against the gRPC service through alb-grpc-sim, with
# concurrency/duration equivalent to benchmark/k6/rest-benchmark.js so the
# two protocols are compared under matching load. Requests cycle round-robin
# across all stores (ghz's behavior when -D is given a JSON array), matching
# how production traffic spreads across many stores rather than just one.
#
# Usage: run.sh [medium|large]
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCENARIO="${1:-large}"
HOST="${GRPC_HOST:-localhost:9443}"
CACERT="${CACERT:-$ROOT_DIR/certs/ca/ca-cert.pem}"
CONCURRENCY="${CONCURRENCY:-20}"
DURATION="${DURATION:-30s}"
STORE_COUNT="${STORE_COUNT:-50}"
PROFILE="${PROFILE:-baseline}"
OUT_DIR="${OUT_DIR:-$ROOT_DIR/benchmark/results}"

case "$SCENARIO" in
  medium) MONTHS_BACK=3 ;;
  large) MONTHS_BACK=12 ;;
  *)
    echo "Unknown scenario: $SCENARIO (expected medium|large)" >&2
    exit 1
    ;;
esac

END_DATE="$(date -u +%Y-%m-%dT00:00:00Z)"
if date -v-1d >/dev/null 2>&1; then
  START_DATE="$(date -u -v-"${MONTHS_BACK}"m +%Y-%m-%dT00:00:00Z)"
else
  START_DATE="$(date -u -d "-${MONTHS_BACK} months" +%Y-%m-%dT00:00:00Z)"
fi

mkdir -p "$OUT_DIR"

DATA_FILE="$(mktemp)"
trap 'rm -f "$DATA_FILE"' EXIT
{
  echo "["
  for i in $(seq 1 "$STORE_COUNT"); do
    sep=","
    [ "$i" -eq "$STORE_COUNT" ] && sep=""
    printf '{"store_id": %d, "start_date": "%s", "end_date": "%s"}%s\n' "$i" "$START_DATE" "$END_DATE" "$sep"
  done
  echo "]"
} > "$DATA_FILE"

ghz --cacert "$CACERT" \
  --call com.benchmark.sales.grpc.SalesService/GetStoreAnnualHistory \
  -c "$CONCURRENCY" \
  -z "$DURATION" \
  --duration-stop=wait \
  -t 90s \
  --max-recv-message-size 20971520 \
  -D "$DATA_FILE" \
  -O json \
  -o "$OUT_DIR/grpc-${SCENARIO}-${PROFILE}.json" \
  "$HOST"

echo "Results written to $OUT_DIR/grpc-${SCENARIO}-${PROFILE}.json"
