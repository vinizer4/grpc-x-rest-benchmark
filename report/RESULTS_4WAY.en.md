# POC: 4 protocols — HTTP/1.1 REST vs HTTP/2 REST vs gRPC vs HTTP/3 + MessagePack

🌐 [Leia isso em Português](RESULTS_4WAY.md)

**Run date:** 2026-09-21
**Environment:** local Docker Compose (Postgres 16, 4 Spring Boot 4.1 / Kotlin services, 4 nginx gateways), containers capped at 1 vCPU / 1GiB (typical EKS pod profile)

This extends the original comparison (see [RESULTS.en.md](RESULTS.en.md)), which only covered REST (HTTP/1.1, JSON) vs gRPC (HTTP/2, Protobuf). This document covers all 4 protocols side by side, without touching anything that already existed: the original `sales-service-rest` service and its results remain exactly as they were.

## 1. What was added

| Variant | Service | Gateway | Port | Serialization |
|---|---|---|---|---|
| HTTP/1.1 REST | `sales-service-rest` (pre-existing) | `api-gateway-sim` | 8443 | JSON |
| HTTP/2 REST | `sales-service-rest-h2` (new) | `api-gateway-sim-h2` (new) | 8543 | JSON |
| gRPC | `sales-service-grpc` (pre-existing) | `alb-grpc-sim` | 9443 | Protobuf |
| HTTP/3 | `sales-service-msgpack` (new) | `edge-h3-sim` (new) | 8843 | MessagePack |

`sales-service-rest-h2` and `sales-service-msgpack` are copies of the same use case (a store's annual history, same shared `sales-domain` module), isolated into their own services — the goal was to isolate each variable (transport protocol, serialization format) without touching the original services.

**Why HTTP/3 needed a different architecture:** no JVM embedded server (Tomcat, Jetty, Netty) has stable server-side HTTP/3 (QUIC) support today. The production-proven approach used by teams already on HTTP/3 is to terminate QUIC/TLS at a dedicated edge (CDN) and speak ordinary HTTP internally — that's what `edge-h3-sim` does: an nginx build (compiled with `--with-http_v3_module`) terminates HTTP/3 on port 8843 and proxies to `sales-service-msgpack` over plain HTTP/1.1. The backend has no idea it's sitting behind HTTP/3.

## 2. Methodology

- Same dataset and the same "large" scenario (12 months, 60,000 sales + 60 promotions, a store picked at random out of 50) used in the original benchmark.
- **Concurrency: 5 simultaneous connections, 20s duration, across all 4 protocols.** That number wasn't arbitrary — it's the result of a real problem hit during testing (section 4): with the large payload (~13MB per response) and the 1GiB-per-container limit (same EKS-pod profile as the original benchmark), concurrency above 10-20 crashed the REST services out of memory. 5 was the point where all 4 protocols completed the run without errors.
- Tools: `k6` for both REST variants (HTTP/1.1 and HTTP/2 — same script, just a different port), `ghz` for gRPC (unchanged), and a custom Python script built on `aioquic` for HTTP/3, since no mature load-testing tool (k6, ghz, wrk) has real HTTP/3 support today. See [benchmark/h3/README.md](../benchmark/h3/README.md).
- **Environment caveat:** the HTTP/3 test does not run from the macOS host — Docker Desktop for macOS does not reliably forward the UDP packets a QUIC handshake needs across the host/VM boundary, even with a correctly configured nginx/TLS/QUIC setup (confirmed: the identical handshake succeeds instantly container-to-container, inside the Compose network). Because of that, the load-test script runs as a container attached to the Compose network, not from the host. This is a local dev-environment limitation, not a flaw in the proposed architecture — in production, behind a real CDN, that host↔VM boundary doesn't exist.

## 3. Latency and throughput

| Protocol | p50 (ms) | p95 (ms) | p99 (ms) | Throughput (req/s) | Success |
|---|---|---|---|---|---|
| gRPC (Protobuf) | 1009 | 3216 | 3526 | 4.17 | 100% |
| HTTP/1.1 REST (JSON) | 1186 | 3457 | 3578 | 3.29 | 100% |
| HTTP/3 (MessagePack) | 1841 | 4772 | 4823 | 2.28 | 100% |
| HTTP/2 REST (JSON) | 1471 | 10367 | 10375 | 1.76 | 100% |

**The most counterintuitive result: HTTP/2 REST had the worst p95/p99 of the four**, despite HTTP/2 usually being sold as a strict upgrade over HTTP/1.1. The likely explanation: HTTP/2's win comes from multiplexing many small requests over one connection — here every request is already a single, enormous response (~13MB) per connection, so there's nothing to multiplex, and HTTP/2's per-stream flow-control overhead (windows, framing) ends up costing more than it helps in a "few large requests" pattern like this one. That's a real lesson from this POC: HTTP/2's advantage depends heavily on the traffic shape, and one giant payload per response isn't where it shines.

gRPC stays ahead by combining HTTP/2 with the lightest payload (Protobuf) — the smaller serialization appears to offset the same per-stream overhead that hurt HTTP/2 REST.

## 4. Payload size

| Protocol | Bytes | Reduction vs JSON |
|---|---|---|
| REST (JSON) | 13,119,848 | — |
| gRPC (Protobuf) | 5,934,663 | 54.8% |
| HTTP/3 (MessagePack) | 11,093,493 | 15.4% |

MessagePack shrinks the payload by being binary and more compact than JSON, but falls well short of Protobuf's reduction — MessagePack still carries field names and has no compiled schema like Protobuf, so the size win is much more modest.

## 5. Memory ceiling: a real finding, not a bug

During testing, both `sales-service-rest` (HTTP/1.1, the original service) and `sales-service-rest-h2` (HTTP/2) got **killed for running out of memory (OOMKilled)** at 10-20 concurrent connections — the same 1GiB limit used since the original benchmark. `sales-service-grpc` and `sales-service-msgpack` survived higher concurrency without crashing.

This isn't a misconfiguration of the new services — it's a direct consequence of serving ~13MB responses as JSON: every in-flight request holds a large serialized copy of the response in memory (Jackson materializes the whole tree before writing it out), and several of those in parallel add up fast against a 1GiB limit sized for a typical pod. Smaller payloads (Protobuf, MessagePack) suffer proportionally less.

**Practical implication for anyone deciding between REST and gRPC/binary protocols for large payloads:** pod memory sizing can't be protocol-agnostic — REST over JSON needs more memory headroom for the same amount of concurrent traffic, which translates directly into infrastructure cost, not just latency.

## 6. Summary

| Aspect | Winner |
|---|---|
| Latency (p50) | gRPC, with HTTP/1.1 REST very close behind |
| Tail latency (p95/p99) | gRPC |
| Payload size | gRPC (Protobuf) |
| Throughput under memory pressure | gRPC, then HTTP/1.1 REST |
| Resilience under high concurrency (no OOM) | gRPC and HTTP/3+MessagePack |
| Worst performer in this scenario | HTTP/2 REST (single large payload, no multiplexing benefit) |

For this specific use case — one large response per request — gRPC remains the strongest technical choice, consistent with the original benchmark's conclusion. Adding these 3 variants reinforces that result rather than contradicting it, and surfaces a useful warning: HTTP/2 alone (without changing serialization) is not an automatic win for every traffic pattern.

---

*How to reproduce: see [benchmark/h3/README.md](../benchmark/h3/README.md) for the HTTP/3 script, and the existing scripts in `benchmark/k6/` and `benchmark/ghz/` for the rest. `python3 benchmark/consolidate/consolidate-4way.py` generates this table and the charts from the raw results.*
