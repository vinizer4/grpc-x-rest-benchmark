# REST vs gRPC: what a real POC taught me about performance, cost, and the trade-offs nobody talks about

🌐 [Ler em português](LINKEDIN_ARTICLE.md)

*After months of hearing "gRPC is faster" with no numbers behind it, I decided to build a complete POC on my own — outside work hours, on my personal computer — with real bugs, honest data, and the same honesty about where gRPC loses as where it wins.*

## Why I did this

Every REST vs gRPC discussion I saw out there ran into the same problem: the benchmarks use toy payloads, perfect networks, and never show the adoption trade-offs. I wanted an answer I could defend with data, not opinion — so I decided to build this as a personal, independent project, with no relation to any employer or real production system.

I set up a complete environment from scratch, simulating a plausible e-commerce/retail use case: querying a store's annual history, returning in one response the aggregated monthly sales for the entire catalog and the period's promotions — a shape similar to what an ETL pipeline typically produces. I didn't pick this scenario at random: it's large enough to expose real payload differences, and representative enough to generalize to other endpoints of the same type.

## How the POC was built

**Architecture:** two Spring Boot/Kotlin services with **the same shared domain logic** — the only difference between them is the exposition layer. This matters: any performance difference comes from the protocol, not from diverging implementations.

**Realistic topology:** both services run behind nginx gateways simulating real AWS — **API Gateway** in front of REST (with API-key auth and rate limiting) and **ALB** in front of gRPC. Why two different gateways? Because **API Gateway doesn't support gRPC pass-through natively** — in practice, adopting gRPC also means touching the infrastructure's edge component, not just swapping a library in the code.

**Data:** 50 stores × 5,000 products × 12 months of history = ~3 million records, generated deterministically (same seed, reproducible) **simulated 4,000 requests per day**.

**Network conditions:** I simulated 4 profiles via `tc`/netem — baseline (no shaping), same-AZ (~0.5ms), cross-AZ (~1.5ms), and cross-region (~100ms) — because testing only on localhost hides exactly the scenario where the difference between protocols shows up the most.

**Load:** k6 for REST, ghz for gRPC, same concurrency on both sides, containers capped at 1 vCPU/1GiB (EKS pod profile) so nobody could cheat with unlimited resources.

## The numbers

**Latency under a bad network** (cross-region, concurrent traffic):
- REST: p99 of **11,034 ms**
- gRPC: p99 of **3,172 ms**
- **71%** reduction

This was the most striking result. The worse the network, the bigger gRPC's multiplexed-HTTP/2 advantage over REST/HTTP1.1 — the difference isn't subtle, it's an order of magnitude in the experience of whoever's waiting for that response.

**Throughput** on the local network, same CPU/memory:
- gRPC sustained **+29% more requests/second**

| Protocol | Scenario | Network profile | p50 (ms) | p95 (ms) | p99 (ms) | Throughput (req/s) | gRPC advantage |
|---|---|---|---|---|---|---|---|
| REST | medium | baseline | 1128 | 1756 | 2153 | 17.2 | |
| gRPC | medium | baseline | 839 | 1517 | 2008 | 22.2 | p50 +26% · thr +29% |
| REST | medium | cross-region | 1876 | 5685 | **11034** | 7.0 | |
| gRPC | medium | cross-region | 1470 | 2494 | **3172** | 12.5 | p50 +22% · p99 +71% · thr +79% |
| REST | large | baseline | 939 | 1316 | 1633 | 5.1 | |
| gRPC | large | baseline | 785 | 1155 | 1564 | 6.2 | p50 +16% · thr +22% |
| REST | large | cross-region | 1194 | 3416 | 4928 | 3.0 | |
| gRPC | large | cross-region | 1472 | 3251 | 3959 | 3.0 | p50 -23% · p99 +20% |

*(condensed table — the full version with all 4 network profiles × 2 scenarios is in the [full report](https://github.com/vinizer4/grpc-x-rest-benchmark/blob/main/report/RESULTS.en.md))*

![p99 latency and throughput comparison — medium payload](images/en/comparison-medium.png)

![p99 latency and throughput comparison — large payload](images/en/comparison-large.png)

**Payload size**, measured byte-for-byte on the wire (not estimated, not the text output from grpcurl that fools a lot of people):

| Scenario | JSON (bytes) | Protobuf (bytes) | Reduction |
|---|---|---|---|
| medium (3 months) | 3,280,037 (~3.3 MB) | 1,483,673 (~1.5 MB) | **54.8%** |
| large (12 months) | 13,119,848 (~13.1 MB) | 5,934,663 (~5.9 MB) | **54.8%** |

![JSON vs Protobuf payload size comparison](images/en/payload-size.png)

**Cost projection** (mathematical extrapolation over the bytes already measured — I did not run the application in real production, I make that clear in the report):

| Endpoints migrated | REST cost/year | gRPC cost/year | Savings/year | REST pods | gRPC pods |
|---|---|---|---|---|---|
| 1 | US$ 3,668 | US$ 2,343 | US$ 1,325 | 5 | 4 |
| 10 | US$ 36,680 | US$ 23,433 | US$ 13,247 | 50 | 40 |
| **20** | **US$ 73,361** | **US$ 46,866** | **US$ 26,495** | 100 | 80 |

![Infrastructure needed as adoption scales](images/en/scale-pods.png)

![Projected cost as adoption scales](images/en/scale-cost.png)

This last point is what I think is most underrated in these discussions: gRPC's cost argument isn't about saving on the data-transfer bill — it's about needing less infrastructure to sustain the same throughput. Compute usually weighs a lot more than egress on the cloud bill.

**The math behind this:**

The egress cost is based on the standard AWS public price (~US$0.09/GB) applied to the actual payload difference I measured (13.1 MB in JSON vs. 5.9 MB in Protobuf for the same 12-month response).

The compute cost uses the standard AWS Fargate public price (~US$32.80/pod/month for 1 vCPU + 1 GiB)—since gRPC handled higher throughput within the same pod, fewer replicas are needed for the same traffic volume.

For a single endpoint, this totals ~US$1,325/year. The US$26,500 figure is a projection based on that calculation applied to 20 endpoints with similar payload and traffic profiles; it does not represent 20 endpoints I actually tested.

I intentionally used a large payload (12 months' worth of data rather than just a single day) because, with smaller payloads, the gap between JSON and Protobuf narrows, making gRPC's advantage much less apparent.

## The bugs I found along the way (the part nobody shows)

Benchmark rigor is this: when a number looks too good or too bad, you have to investigate before trusting it. Three examples:

1. **Insufficient JVM heap**: the default (25% of the container limit) left only ~256MB of heap in a 1GiB container — not enough for a 13MB payload under load. Result: `OutOfMemoryError` in **both** services, not just gRPC. It wasn't a protocol difference, it was a missing production configuration.

2. **netem and pathological TCP retransmission**: simulating 0.05% packet loss to represent cross-region caused a single 3MB request to take as long as **80 seconds** in Docker Desktop's virtualized environment — disproportionate to any real condition. I had to simplify to fixed delay with no jitter/loss to get reliable numbers.

3. **ghz cuts off in-flight requests**: unlike k6 (which waits for in-flight iterations to finish), `ghz` by default drops connections the exact second the test duration ends — this artificially inflated gRPC's error rate until I found the `--duration-stop=wait` flag.

None of these bugs invalidated the comparison — but ignoring them would have produced wrong numbers, and I'd rather delay delivery than present a result that wouldn't survive a second look.

## Where gRPC loses (and this matters as much as where it wins)

A POC that only shows advantages isn't trustworthy. The report documents, with the same honesty:

- **Binary payload**: you can't inspect it with a quick `curl` or paste it into Postman and read it — you need a tool that understands the `.proto`.
- **Stub generation and versioning**: the contract needs to stay in sync between publisher and consumer, with codegen on every build.
- **No native browser support**: an SPA calling the service directly needs grpc-web or a proxy in between.
- **Observability maturity**: tools like New Relic still have more mature support for REST than for gRPC.
- **The gateway swap** already mentioned — a platform architecture decision, not just an application-team one.

## This isn't isolated theory

gRPC has been running in production for years at companies with dedicated performance teams: **Google** (creator, evolution of the internal Stubby framework), **Netflix** (Protobuf/gRPC in internal API design), **Uber** (gRPC-based API gateway), **Square** (replaced their own RPC citing performance as the motivator) — plus Cloudflare, Datadog, Salesforce, DoorDash, and others in the project's official showcase. It's not a bet on niche technology.

## Where this left me

The data doesn't support a blanket migration — it supports **selective adoption**: internal endpoints (service-to-service, not exposed to a browser) with meaningful concurrent traffic and/or network-latency sensitivity are the obvious candidates. Huge payloads at low concurrency run into the server's memory bottleneck, not the protocol's — there, it's worth investing more in pagination/streaming than in switching protocols.

![Performance and cost summary](images/en/final-summary.png)

## Everything is open

Full source code, benchmark scripts (k6, ghz, network simulation), both services, the gateways, and the complete report with all the charts and detailed methodology are public:

🔗 **https://github.com/vinizer4/grpc-x-rest-benchmark**

If you've been through this decision on your team, or disagree with any assumption I made, I'd really like to hear it. Comment here or open an issue in the repo.

#gRPC #REST #backend #performance #cloud #kotlin #springboot #softwarearchitecture
