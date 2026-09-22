# HTTP/3 load test

Load-testing tool for the HTTP/3 (QUIC) + MessagePack variant, served by
`edge-h3-sim` in front of `sales-service-msgpack`.

## Why not k6

k6 has no HTTP/3 support, and no mature k6/ghz-equivalent tool does either.
This is a small `aioquic`-based Python script (`load_test.py`) instead:
each concurrent worker holds one persistent QUIC connection and issues GET
requests against random stores back-to-back until the configured duration
elapses, then a JSON summary (percentile latency, throughput, success rate)
is written to `benchmark/results/`, in the same spirit as the k6/ghz result
files.

## Why it must run as a container on the compose network

Docker Desktop for macOS does not reliably forward the UDP packets a QUIC
handshake needs across the host/VM boundary — `curl --http3-only` from the
host against the published port (`127.0.0.1:8843`) times out during the
handshake, even though nginx's QUIC listener is correctly configured
(confirmed via `nginx -V` showing `--with-http_v3_module`, `nginx -t`
passing, and `netstat` showing the UDP socket bound). The same request
against `edge-h3-sim:8443` from a container attached to the compose network
succeeds immediately (ALPN negotiates `h3`), because it never crosses that
host/VM boundary. This looks specific to Docker Desktop's macOS networking
layer (vpnkit), not to this project's nginx/TLS/QUIC configuration.

Practically, this means: don't run `load_test.py` from the host. Use
`run.sh`, which runs it in a container on `grpc-x-rest-benchmark_default`.

## Usage

```bash
# bring the stack up first (docker compose up -d)
./run.sh large     # or: ./run.sh medium
```

Environment variables (all optional, matching the style of
`benchmark/ghz/run.sh`): `CONCURRENCY`, `DURATION_S`, `STORE_COUNT`,
`PROFILE`, `BASE_URL`, `NETWORK`, `OUT_DIR`.

Output: `benchmark/results/h3-<scenario>-<profile>.json`.
