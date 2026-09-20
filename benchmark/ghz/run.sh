#!/usr/bin/env bash
# Runs a ghz load test against the gRPC service through alb-grpc-sim, with
# concurrency/duration equivalent to benchmark/k6/rest-benchmark.js so the
# two protocols are compared under matching load.
#
# Usage: run.sh [medium|large]
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCENARIO="${1:-large}"
HOST="${GRPC_HOST:-localhost:9443}"
CACERT="${CACERT:-$ROOT_DIR/certs/ca/ca-cert.pem}"
CONCURRENCY="${CONCURRENCY:-20}"
DURATION="${DURATION:-30s}"
PRODUTO_ID="${PRODUTO_ID:-311}"
OUT_DIR="${OUT_DIR:-$ROOT_DIR/benchmark/results}"

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

mkdir -p "$OUT_DIR"

ghz --cacert "$CACERT" \
  --call com.benchmark.sales.grpc.SalesService/ConsultarVendasPorProduto \
  -c "$CONCURRENCY" \
  -z "$DURATION" \
  -d "{\"id_produto\": $PRODUTO_ID, \"data_inicio\": \"$DATA_INICIO\", \"data_fim\": \"$DATA_FIM\"}" \
  -O json \
  -o "$OUT_DIR/grpc-${SCENARIO}.json" \
  "$HOST"

echo "Results written to $OUT_DIR/grpc-${SCENARIO}.json"
