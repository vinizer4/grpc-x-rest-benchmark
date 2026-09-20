#!/usr/bin/env bash
# Applies a tc/netem latency profile to the gateway containers' egress traffic,
# simulating how far the client is from the load balancer/API Gateway in a
# real AWS deployment (same-AZ, cross-AZ same-region, or cross-region).
#
# Usage: apply-profile.sh <baseline|same-az|cross-az|cross-region> [container...]
# Defaults to both gateway containers if none are given.
set -euo pipefail

PROFILE="${1:?Usage: apply-profile.sh <baseline|same-az|cross-az|cross-region> [container...]}"
shift || true

if [ "$#" -gt 0 ]; then
  CONTAINERS=("$@")
else
  CONTAINERS=(grpc-x-rest-benchmark-api-gateway-sim-1 grpc-x-rest-benchmark-alb-grpc-sim-1)
fi

case "$PROFILE" in
  baseline)
    NETEM_ARGS=""
    ;;
  same-az)
    # ~0.5ms delay, minimal jitter: two pods in the same AZ.
    NETEM_ARGS="delay 0.5ms 0.1ms distribution normal"
    ;;
  cross-az)
    # ~1-2ms delay, small jitter: two pods in different AZs, same region.
    NETEM_ARGS="delay 1.5ms 0.5ms distribution normal"
    ;;
  cross-region)
    # ~50-150ms delay, larger jitter, small packet loss: cross-region traffic.
    NETEM_ARGS="delay 100ms 20ms distribution normal loss 0.05%"
    ;;
  *)
    echo "Unknown profile: $PROFILE (expected baseline|same-az|cross-az|cross-region)" >&2
    exit 1
    ;;
esac

for container in "${CONTAINERS[@]}"; do
  docker exec "$container" tc qdisc del dev eth0 root 2>/dev/null || true
  if [ -n "$NETEM_ARGS" ]; then
    # shellcheck disable=SC2086
    docker exec "$container" tc qdisc add dev eth0 root netem $NETEM_ARGS
    echo "Applied '$PROFILE' ($NETEM_ARGS) to $container"
  else
    echo "Cleared network shaping on $container (baseline)"
  fi
done
