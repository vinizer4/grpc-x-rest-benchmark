# grpc-x-rest-benchmark

🌐 [Ler em português](README.md)

POC comparing performance and cost between REST (JSON) and gRPC (Protobuf) for the same use case: querying a store's annual history (aggregated monthly sales + promotions). Both services share the same domain logic (`sales-domain`) and sit behind gateways that simulate the real AWS topology — API Gateway in front of REST, ALB in front of gRPC (API Gateway does not support gRPC pass-through natively).

Full report with results, charts, and cost analysis: [`report/RESULTS.en.md`](report/RESULTS.en.md).

## Architecture

```
client → api-gateway-sim (nginx :8443, TLS + API key)  → sales-service-rest  (:8080, TLS)
client → alb-grpc-sim     (nginx :9443, TLS)            → sales-service-grpc (:9090, TLS)
                                                              ↓
                                                          postgres (:5432)
```

- `sales-domain`: JPA entities, repositories, and the query logic shared by both services
- `sales-service-rest`: `GET /stores/{storeId}/annual-history`
- `sales-service-grpc`: `SalesService/GetStoreAnnualHistory` (contract in [`proto/sales.proto`](proto/sales.proto))
- `datagen`: generates the synthetic dataset (stores, products, monthly sales, promotions)
- `gateway/api-gateway-sim` and `gateway/alb-grpc-sim`: nginx simulating API Gateway and ALB
- `benchmark/`: load scripts (k6/ghz), network shaping, resource monitor, and results consolidation

## Prerequisites

- Docker and Docker Compose
- `openssl` and `keytool` (JDK) — to generate the local TLS certificates
- [`curl`](https://curl.se/) — to call the REST endpoint (usually already installed on macOS/Linux)
- [`grpcurl`](https://github.com/fullstorydev/grpcurl) — to call the gRPC endpoint from the command line

```bash
brew install curl grpcurl   # macOS
```

Optional, only needed to run the load benchmarks (section [Running the benchmarks](#running-the-benchmarks)):

- [`k6`](https://k6.io/) — load on the REST endpoint
- [`ghz`](https://ghz.sh/) — load on the gRPC endpoint

```bash
brew install k6 ghz   # macOS
```

## Bringing the environment up

**1. Generate the TLS certificates** (self-signed, used by both services and both gateways):

```bash
./certs/generate-certs.sh
```

**2. Start the services**:

```bash
docker compose up -d --build
```

This brings up Postgres, `sales-service-rest`, `sales-service-grpc`, `api-gateway-sim`, and `alb-grpc-sim`. Flyway migrations run automatically the first time the services start.

**3. Populate the dataset** (`datagen` profile, runs once and exits — the services need to have started at least once so the schema exists):

```bash
docker compose --profile datagen run --rm datagen
```

By default it generates 50 stores × 5,000 products × 12 months (~3M sales rows + 3,000 promotions). For a different volume:

```bash
docker compose --profile datagen run --rm \
  -e STORE_COUNT=10 -e TOTAL_PRODUCTS=1000 -e MONTHS=6 \
  datagen
```

**4. Confirm everything is up:**

```bash
docker compose ps
```

## Accessing the REST endpoint

Goes through `api-gateway-sim` (port `8443`, TLS + mandatory API key — without it you get `401`):

```bash
curl --cacert certs/ca/ca-cert.pem \
  -H "X-Api-Key: benchmark-poc-api-key" \
  "https://localhost:8443/stores/1/annual-history?startDate=2025-09-20&endDate=2026-09-20"
```

- `storeId`: 1 to 50 (or whatever `STORE_COUNT` value you used in `datagen`)
- `startDate`/`endDate`: `YYYY-MM-DD`

### Testing via Postman

1. **New → HTTP Request**, method `GET`
2. URL: `https://localhost:8443/stores/1/annual-history?startDate=2025-09-20&endDate=2026-09-20`
3. **Headers** tab: add `X-Api-Key: benchmark-poc-api-key` (without it the gateway responds `401`)
4. Since the certificate is self-signed, disable TLS verification under Settings → General → **"SSL certificate verification"** (or import `certs/ca/ca-cert.pem` under Settings → Certificates → CA Certificates, pointing to `localhost`)
5. **Send**

For the 12-month range the response is large (~13MB); if you'd rather inspect a smaller response in Postman, use a shorter range (e.g. `startDate=2026-06-20`).

## Accessing the gRPC endpoint

Goes through `alb-grpc-sim` (port `9443`, TLS, no authentication — see section 5 of the report on this asymmetry). Use `grpcurl`:

```bash
grpcurl -cacert certs/ca/ca-cert.pem -max-msg-sz 20000000 \
  -d '{"store_id": 1, "start_date": "2025-09-20T00:00:00Z", "end_date": "2026-09-20T00:00:00Z"}' \
  localhost:9443 com.benchmark.sales.grpc.SalesService/GetStoreAnnualHistory
```

- `-max-msg-sz 20000000` is needed for large responses (the 12-month history goes past 4MB, gRPC's default limit)
- The server runs with reflection enabled, so `grpcurl` works without pointing at the `.proto` manually. To list the available services/methods:

```bash
grpcurl -cacert certs/ca/ca-cert.pem localhost:9443 list
grpcurl -cacert certs/ca/ca-cert.pem localhost:9443 describe com.benchmark.sales.grpc.SalesService
```

### Testing via Postman

Postman has native gRPC support (Postman **Desktop**, v9+ — doesn't work on Postman Web). Since reflection is enabled, it discovers the service on its own, no need to import the `.proto`:

1. **New → gRPC Request**
2. Server URL: `localhost:9443`
3. Since the server uses TLS with a self-signed certificate, either:
   - Settings → Certificates → **CA Certificates** → add `certs/ca/ca-cert.pem`, or
   - Settings → General → disable **"SSL certificate verification"** (simpler, fine for a local POC)
4. Click **"Select a method"** — Postman queries the server's reflection and lists `com.benchmark.sales.grpc.SalesService/GetStoreAnnualHistory` automatically
5. Fill in the message body:
   ```json
   {
     "store_id": 1,
     "start_date": "2025-09-20T00:00:00Z",
     "end_date": "2026-09-20T00:00:00Z"
   }
   ```
6. **Invoke**

For the 12-month scenario (~13MB REST / ~5.9MB Protobuf), the response is large — if Postman complains about size, try a shorter date range first (e.g. 3 months) before the full year.

## Running the benchmarks

```bash
# Apply a network profile (baseline|same-az|cross-az|cross-region)
./benchmark/network-shaping/apply-profile.sh baseline

# REST load (k6) and gRPC load (ghz) — medium scenario (3 months) or large (12 months)
VUS=20 DURATION=20s SCENARIO=medium PROFILE=baseline STORE_COUNT=50 k6 run benchmark/k6/rest-benchmark.js
CONCURRENCY=20 DURATION=20s PROFILE=baseline STORE_COUNT=50 ./benchmark/ghz/run.sh medium

# JSON vs Protobuf payload size (real bytes on the wire, not grpcurl's text output)
./benchmark/measure-payload-size.sh medium

# CPU/memory during a load run
./benchmark/resource-monitor/monitor.sh benchmark/results/resources.csv 20 grpc-x-rest-benchmark-sales-service-rest-1

# Consolidate everything into tables/charts
python3 benchmark/consolidate/consolidate.py
```

Results go to `benchmark/results/` (git-ignored — these are run artifacts, not versioned).

## Stopping/cleaning up

```bash
docker compose down       # stops the containers, keeps the data
docker compose down -v    # stops the containers and wipes the Postgres volume
```
