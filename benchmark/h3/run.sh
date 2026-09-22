#!/usr/bin/env bash
# Runs the HTTP/3 (QUIC) load test against edge-h3-sim, with concurrency/
# duration equivalent to benchmark/k6/rest-benchmark.js and benchmark/ghz/run.sh
# so all protocols are compared under matching load.
#
# Must run as a container attached to the compose network (edge-h3-sim
# resolved by service name): Docker Desktop for macOS does not reliably
# forward the UDP packets a QUIC handshake needs from the host, even though
# the same handshake succeeds instantly container-to-container. See
# benchmark/h3/README.md.
#
# Usage: run.sh [medium|large]
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCENARIO="${1:-large}"
NETWORK="${NETWORK:-grpc-x-rest-benchmark_default}"
CACERT_HOST_PATH="${CACERT_HOST_PATH:-$ROOT_DIR/certs/ca/ca-cert.pem}"
CONCURRENCY="${CONCURRENCY:-20}"
DURATION_S="${DURATION_S:-30}"
STORE_COUNT="${STORE_COUNT:-50}"
PROFILE="${PROFILE:-baseline}"
BASE_URL="${BASE_URL:-https://edge-h3-sim:8443}"
OUT_DIR="${OUT_DIR:-$ROOT_DIR/benchmark/results}"

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

mkdir -p "$OUT_DIR"

docker build -q -t benchmark-h3-load-test "$ROOT_DIR/benchmark/h3" >/dev/null

docker run --rm \
  --network "$NETWORK" \
  -v "$CACERT_HOST_PATH:/certs/ca-cert.pem:ro" \
  -v "$OUT_DIR:/results" \
  -e CACERT=/certs/ca-cert.pem \
  benchmark-h3-load-test \
  --url "$BASE_URL" \
  --concurrency "$CONCURRENCY" \
  --duration "$DURATION_S" \
  --store-count "$STORE_COUNT" \
  --scenario "$SCENARIO" \
  --profile "$PROFILE" \
  --start-date "$START_DATE" \
  --end-date "$END_DATE" \
  --out-dir /results

echo "Results written to $OUT_DIR/h3-${SCENARIO}-${PROFILE}.json"
