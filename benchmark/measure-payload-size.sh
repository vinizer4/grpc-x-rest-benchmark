#!/usr/bin/env bash
# Compares serialized payload size (bytes) for the same query, JSON (REST,
# via api-gateway-sim) vs Protobuf (gRPC, via alb-grpc-sim). This is one of
# the POC's core deliverable metrics, independent of latency/throughput.
#
# Usage: measure-payload-size.sh [medium|large]
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCENARIO="${1:-large}"
REST_HOST="${REST_HOST:-https://localhost:8443}"
GRPC_HOST="${GRPC_HOST:-localhost:9443}"
API_KEY="${API_KEY:-benchmark-poc-api-key}"
STORE_ID="${STORE_ID:-1}"
CACERT="$ROOT_DIR/certs/ca/ca-cert.pem"

case "$SCENARIO" in
  medium) MONTHS_BACK=3 ;;
  large) MONTHS_BACK=12 ;;
  *)
    echo "Unknown scenario: $SCENARIO (expected medium|large)" >&2
    exit 1
    ;;
esac

END_DATE="$(date -u +%Y-%m-%d)"
if date -v-1d >/dev/null 2>&1; then
  START_DATE="$(date -u -v-"${MONTHS_BACK}"m +%Y-%m-%d)"
else
  START_DATE="$(date -u -d "-${MONTHS_BACK} months" +%Y-%m-%d)"
fi

echo "Scenario: $SCENARIO (store $STORE_ID, $START_DATE -> $END_DATE)"

OUTPUT="$(GRPC_HOST="$GRPC_HOST" REST_BASE_URL="$REST_HOST" API_KEY="$API_KEY" STORE_ID="$STORE_ID" \
  START_DATE="$START_DATE" END_DATE="$END_DATE" CACERT="$CACERT" \
  "$ROOT_DIR/gradlew" -p "$ROOT_DIR" :sales-service-grpc:payloadSize --console=plain -q)"

echo "$OUTPUT"

JSON_BYTES="$(echo "$OUTPUT" | grep 'REST (JSON) bytes:' | grep -oE '[0-9]+')"
PROTO_BYTES="$(echo "$OUTPUT" | grep 'gRPC (Protobuf) bytes:' | grep -oE '[0-9]+')"
REDUCTION="$(echo "$OUTPUT" | grep -i 'reduction' | grep -oE '[0-9.]+')"

CSV_FILE="$ROOT_DIR/benchmark/results/payload-sizes.csv"
mkdir -p "$(dirname "$CSV_FILE")"
if [ ! -f "$CSV_FILE" ]; then
  echo "scenario,store_id,start_date,end_date,json_bytes,proto_bytes,reduction_pct" > "$CSV_FILE"
fi
echo "$SCENARIO,$STORE_ID,$START_DATE,$END_DATE,$JSON_BYTES,$PROTO_BYTES,$REDUCTION" >> "$CSV_FILE"
echo "Recorded to $CSV_FILE"
