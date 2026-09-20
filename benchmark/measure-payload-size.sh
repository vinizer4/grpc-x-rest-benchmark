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
PRODUTO_ID="${PRODUTO_ID:-311}"
CACERT="$ROOT_DIR/certs/ca/ca-cert.pem"

case "$SCENARIO" in
  medium) DIAS_ATRAS=30 ;;
  large) DIAS_ATRAS=380 ;;
  *)
    echo "Unknown scenario: $SCENARIO (expected medium|large)" >&2
    exit 1
    ;;
esac

DATA_FIM="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
if date -v-1d >/dev/null 2>&1; then
  DATA_INICIO="$(date -u -v-"${DIAS_ATRAS}"d +%Y-%m-%dT%H:%M:%SZ)"
else
  DATA_INICIO="$(date -u -d "-${DIAS_ATRAS} days" +%Y-%m-%dT%H:%M:%SZ)"
fi

echo "Cenario: $SCENARIO (produto $PRODUTO_ID, $DATA_INICIO -> $DATA_FIM)"

GRPC_HOST="$GRPC_HOST" REST_BASE_URL="$REST_HOST" API_KEY="$API_KEY" PRODUTO_ID="$PRODUTO_ID" \
  DATA_INICIO="$DATA_INICIO" DATA_FIM="$DATA_FIM" CACERT="$CACERT" \
  "$ROOT_DIR/gradlew" -p "$ROOT_DIR" :sales-service-grpc:payloadSize --console=plain -q
