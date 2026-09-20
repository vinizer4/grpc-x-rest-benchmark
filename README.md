# grpc-x-rest-benchmark

POC comparando desempenho e custo entre REST (JSON) e gRPC (Protobuf) para o mesmo caso de uso: consulta do histórico anual de uma loja (vendas mensais agregadas + promoções). Os dois serviços compartilham a mesma lógica de domínio (`sales-domain`) e ficam atrás de gateways que simulam a topologia real da AWS — API Gateway na frente do REST, ALB na frente do gRPC (API Gateway não suporta proxy gRPC nativamente).

Relatório completo com resultados, gráficos e análise de custo: [`report/RESULTS.md`](report/RESULTS.md).

## Arquitetura

```
client → api-gateway-sim (nginx :8443, TLS + API key)  → sales-service-rest  (:8080, TLS)
client → alb-grpc-sim     (nginx :9443, TLS)            → sales-service-grpc (:9090, TLS)
                                                              ↓
                                                          postgres (:5432)
```

- `sales-domain`: entidades JPA, repositórios e a lógica de consulta compartilhada pelos dois serviços
- `sales-service-rest`: `GET /stores/{storeId}/annual-history`
- `sales-service-grpc`: `SalesService/GetStoreAnnualHistory` (contrato em [`proto/sales.proto`](proto/sales.proto))
- `datagen`: gera a massa de dados sintética (lojas, produtos, vendas mensais, promoções)
- `gateway/api-gateway-sim` e `gateway/alb-grpc-sim`: nginx simulando API Gateway e ALB
- `benchmark/`: scripts de carga (k6/ghz), shaping de rede, monitor de recursos e consolidação de resultados

## Pré-requisitos

- Docker e Docker Compose
- `openssl` e `keytool` (JDK) — para gerar os certificados TLS locais
- [`curl`](https://curl.se/) — para chamar o endpoint REST (já vem instalado no macOS/Linux na maioria dos casos)
- [`grpcurl`](https://github.com/fullstorydev/grpcurl) — para chamar o endpoint gRPC pela linha de comando

```bash
brew install curl grpcurl   # macOS
```

Opcional, só para rodar os benchmarks de carga (seção [Rodando os benchmarks](#rodando-os-benchmarks)):

- [`k6`](https://k6.io/) — carga no endpoint REST
- [`ghz`](https://ghz.sh/) — carga no endpoint gRPC

```bash
brew install k6 ghz   # macOS
```

## Subindo o ambiente

**1. Gerar os certificados TLS** (self-signed, usados pelos dois serviços e pelos dois gateways):

```bash
./certs/generate-certs.sh
```

**2. Subir os serviços**:

```bash
docker compose up -d --build
```

Isso sobe Postgres, `sales-service-rest`, `sales-service-grpc`, `api-gateway-sim` e `alb-grpc-sim`. As migrações do Flyway rodam automaticamente na primeira inicialização dos serviços.

**3. Popular a massa de dados** (perfil `datagen`, roda uma vez e sai — precisa que os serviços já tenham subido ao menos uma vez para o schema existir):

```bash
docker compose --profile datagen run --rm datagen
```

Por padrão gera 50 lojas × 5.000 produtos × 12 meses (~3M linhas de vendas + 3 mil promoções). Para outro volume:

```bash
docker compose --profile datagen run --rm \
  -e STORE_COUNT=10 -e TOTAL_PRODUCTS=1000 -e MONTHS=6 \
  datagen
```

**4. Conferir que subiu tudo:**

```bash
docker compose ps
```

## Acessando o endpoint REST

Vai por `api-gateway-sim` (porta `8443`, TLS + API key obrigatória — sem a chave retorna `401`):

```bash
curl --cacert certs/ca/ca-cert.pem \
  -H "X-Api-Key: benchmark-poc-api-key" \
  "https://localhost:8443/stores/1/annual-history?startDate=2025-09-20&endDate=2026-09-20"
```

- `storeId`: 1 a 50 (ou o valor de `STORE_COUNT` usado no `datagen`)
- `startDate`/`endDate`: `YYYY-MM-DD`

### Testando via Postman

1. **New → HTTP Request**, método `GET`
2. URL: `https://localhost:8443/stores/1/annual-history?startDate=2025-09-20&endDate=2026-09-20`
3. Aba **Headers**: adicione `X-Api-Key: benchmark-poc-api-key` (sem isso o gateway responde `401`)
4. Como o certificado é self-signed, desative a verificação de TLS em Settings → General → **"SSL certificate verification"** (ou importe `certs/ca/ca-cert.pem` em Settings → Certificates → CA Certificates, apontando para `localhost`)
5. **Send**

Para o intervalo de 12 meses a resposta é grande (~13MB); se preferir uma resposta menor para inspecionar no Postman, use um intervalo de poucos meses (ex: `startDate=2026-06-20`).

## Acessando o endpoint gRPC

Vai por `alb-grpc-sim` (porta `9443`, TLS, sem autenticação — ver seção 5 do relatório sobre essa assimetria). Use `grpcurl`:

```bash
grpcurl -cacert certs/ca/ca-cert.pem -max-msg-sz 20000000 \
  -d '{"store_id": 1, "start_date": "2025-09-20T00:00:00Z", "end_date": "2026-09-20T00:00:00Z"}' \
  localhost:9443 com.benchmark.sales.grpc.SalesService/GetStoreAnnualHistory
```

- `-max-msg-sz 20000000` é necessário para respostas grandes (histórico de 12 meses passa de 4MB, o limite padrão do gRPC)
- O servidor roda com reflection habilitado, então `grpcurl` funciona sem precisar apontar para o `.proto` manualmente. Para listar os serviços/métodos disponíveis:

```bash
grpcurl -cacert certs/ca/ca-cert.pem localhost:9443 list
grpcurl -cacert certs/ca/ca-cert.pem localhost:9443 describe com.benchmark.sales.grpc.SalesService
```

### Testando via Postman

O Postman tem suporte nativo a gRPC (Postman **Desktop**, v9+ — não funciona no Postman Web). Como a reflection está habilitada, ele descobre o serviço sozinho, sem precisar importar o `.proto`:

1. **New → gRPC Request**
2. URL do servidor: `localhost:9443`
3. Como o servidor usa TLS com certificado self-signed, ou:
   - Settings → Certificates → **CA Certificates** → adicione `certs/ca/ca-cert.pem`, ou
   - Settings → General → desative **"SSL certificate verification"** (mais simples, ok para uma POC local)
4. Clique em **"Select a method"** — o Postman consulta a reflection do servidor e lista `com.benchmark.sales.grpc.SalesService/GetStoreAnnualHistory` automaticamente
5. No corpo da mensagem, preencha:
   ```json
   {
     "store_id": 1,
     "start_date": "2025-09-20T00:00:00Z",
     "end_date": "2026-09-20T00:00:00Z"
   }
   ```
6. **Invoke**

Para o cenário de 12 meses (~13MB REST / ~5,9MB Protobuf), a resposta é grande — se o Postman reclamar de tamanho, teste primeiro com um intervalo de datas menor (ex: 3 meses) antes de tentar o ano completo.

## Rodando os benchmarks

```bash
# Aplicar um perfil de rede (baseline|same-az|cross-az|cross-region)
./benchmark/network-shaping/apply-profile.sh baseline

# Carga REST (k6) e gRPC (ghz) — cenário medium (3 meses) ou large (12 meses)
VUS=20 DURATION=20s SCENARIO=medium PROFILE=baseline STORE_COUNT=50 k6 run benchmark/k6/rest-benchmark.js
CONCURRENCY=20 DURATION=20s PROFILE=baseline STORE_COUNT=50 ./benchmark/ghz/run.sh medium

# Tamanho de payload JSON vs Protobuf (bytes reais no wire, não a saída em texto do grpcurl)
./benchmark/measure-payload-size.sh medium

# CPU/memória durante uma carga
./benchmark/resource-monitor/monitor.sh benchmark/results/resources.csv 20 grpc-x-rest-benchmark-sales-service-rest-1

# Consolidar tudo em tabelas/gráficos
python3 benchmark/consolidate/consolidate.py
```

Resultados vão para `benchmark/results/` (ignorado pelo git — são artefatos de execução, não versionados).

## Parando/limpando

```bash
docker compose down       # para os containers, mantém os dados
docker compose down -v    # para os containers e apaga o volume do Postgres
```
