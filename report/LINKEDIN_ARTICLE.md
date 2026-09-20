# REST vs gRPC: o que uma POC real me ensinou sobre performance, custo e os trade-offs que ninguém comenta

🌐 [Read this in English](LINKEDIN_ARTICLE.en.md)

*Depois de meses ouvindo "gRPC é mais rápido" sem nenhum número por trás, decidi construir uma POC completa por conta própria — fora do horário de trabalho, no meu computador pessoal — com bugs reais, dados honestos e a mesma sinceridade sobre onde gRPC perde quanto sobre onde ele ganha.*

## Por que fazer isso

Toda discussão sobre REST vs gRPC que eu via por aí esbarrava no mesmo problema: os benchmarks usam payloads de brinquedo, redes perfeitas, e nunca mostram os trade-offs de adoção. Eu queria uma resposta que eu pudesse defender com dados, não com opinião — então resolvi construir isso como um projeto pessoal, independente, sem relação com nenhum empregador ou sistema de produção real.

Montei um ambiente completo, do zero, simulando um caso de uso plausível de e-commerce/varejo: consulta do histórico anual de uma loja, trazendo em uma resposta as vendas mensais agregadas de todo o catálogo e as promoções do período — um formato parecido com o que um pipeline de ETL costuma produzir. Não escolhi esse cenário à toa: é grande o suficiente pra expor diferenças reais de payload, e representativo o suficiente pra generalizar pra outros endpoints do mesmo tipo.

## Como a POC foi montada

**Arquitetura:** dois serviços Spring Boot/Kotlin com a **mesma lógica de domínio compartilhada** — a única diferença entre eles é a camada de exposição. Isso é importante: qualquer diferença de performance vem do protocolo, não de implementações divergentes.

**Topologia realista:** os dois serviços rodam atrás de gateways nginx simulando a AWS de verdade — **API Gateway** na frente do REST (com autenticação por API key e rate limiting) e **ALB** na frente do gRPC. Por quê dois gateways diferentes? Porque **API Gateway não suporta proxy gRPC nativamente** — na prática, adotar gRPC também significa mexer no componente de borda da infraestrutura, não é só trocar uma lib no código.

**Dados:** 50 lojas × 5.000 produtos × 12 meses de histórico = ~3 milhões de registros, gerados de forma determinística (mesma seed, reproduzível).

**Condições de rede:** simulei 4 perfis via `tc`/netem — baseline (sem shaping), same-AZ (~0,5ms), cross-AZ (~1,5ms) e cross-region (~100ms) — porque testar só em localhost esconde exatamente o cenário onde a diferença entre protocolos mais aparece.

**Carga:** k6 pro REST, ghz pro gRPC, mesma concorrência dos dois lados, containers limitados a 1 vCPU/1GiB (perfil de pod EKS) pra ninguém trapacear com recursos ilimitados.

## Os números

**Latência sob rede ruim** (cross-region, tráfego concorrente):
- REST: p99 de **11.034 ms**
- gRPC: p99 de **3.172 ms**
- Redução de **71%**

Esse foi o resultado mais contundente. Quanto pior a rede, maior a vantagem do HTTP/2 multiplexado do gRPC sobre o REST/HTTP1.1 — a diferença não é sutil, é uma ordem de grandeza na experiência de quem espera essa resposta.

**Throughput** na rede local, mesma CPU/memória:
- gRPC sustentou **+29% mais requisições/segundo**

| Protocolo | Cenário | Perfil de rede | p50 (ms) | p95 (ms) | p99 (ms) | Throughput (req/s) | Vantagem do gRPC |
|---|---|---|---|---|---|---|---|
| REST | médio | baseline | 1128 | 1756 | 2153 | 17,2 | |
| gRPC | médio | baseline | 839 | 1517 | 2008 | 22,2 | p50 +26% · thr +29% |
| REST | médio | cross-region | 1876 | 5685 | **11034** | 7,0 | |
| gRPC | médio | cross-region | 1470 | 2494 | **3172** | 12,5 | p50 +22% · p99 +71% · thr +79% |
| REST | grande | baseline | 939 | 1316 | 1633 | 5,1 | |
| gRPC | grande | baseline | 785 | 1155 | 1564 | 6,2 | p50 +16% · thr +22% |
| REST | grande | cross-region | 1194 | 3416 | 4928 | 3,0 | |
| gRPC | grande | cross-region | 1472 | 3251 | 3959 | 3,0 | p50 -23% · p99 +20% |

*(tabela condensada — a versão completa com os 4 perfis de rede × 2 cenários está no [relatório completo](https://github.com/vinizer4/grpc-x-rest-benchmark/blob/main/report/RESULTS.md))*

![Comparativo de latência p99 e throughput — payload médio](images/comparison-medium.png)

![Comparativo de latência p99 e throughput — payload grande](images/comparison-large.png)

**Tamanho de payload**, medido byte a byte no wire (não estimado, não é a saída em texto do grpcurl que engana muita gente):

| Cenário | JSON (bytes) | Protobuf (bytes) | Redução |
|---|---|---|---|
| médio (3 meses) | 3.280.037 (~3,3 MB) | 1.483.673 (~1,5 MB) | **54,8%** |
| grande (12 meses) | 13.119.848 (~13,1 MB) | 5.934.663 (~5,9 MB) | **54,8%** |

![Comparativo de tamanho de payload JSON vs Protobuf](images/payload-size.png)

**Projeção de custo** (extrapolação matemática sobre os bytes já medidos — não rodei a aplicação em produção real, deixo isso claro no relatório):

| Endpoints migrados | Custo REST/ano | Custo gRPC/ano | Economia/ano | Pods REST | Pods gRPC |
|---|---|---|---|---|---|
| 1 | US$ 3.668 | US$ 2.343 | US$ 1.325 | 5 | 4 |
| 10 | US$ 36.680 | US$ 23.433 | US$ 13.247 | 50 | 40 |
| **20** | **US$ 73.361** | **US$ 46.866** | **US$ 26.495** | 100 | 80 |

![Infraestrutura necessária ao escalar](images/scale-pods.png)

![Custo projetado ao escalar](images/scale-cost.png)

Esse último ponto é o que eu acho mais subestimado nessas discussões: o argumento de custo de gRPC não é sobre economizar na conta de transferência de dados — é sobre precisar de menos infraestrutura pra sustentar o mesmo throughput. Compute geralmente pesa muito mais que egress na fatura de nuvem.

## Os bugs que encontrei no caminho (a parte que ninguém mostra)

Rigor de benchmark é isso: quando um número parece bom demais ou ruim demais, tem que investigar antes de confiar nele. Três exemplos:

1. **Heap da JVM insuficiente**: o padrão (25% do limite do container) deixava só ~256MB de heap num container de 1GiB — insuficiente pra um payload de 13MB sob carga. Resultado: `OutOfMemoryError` nos **dois** serviços, não só no gRPC. Não era diferença de protocolo, era configuração de produção que faltava.

2. **netem e retransmissão TCP patológica**: ao simular 0,05% de perda de pacote pra representar cross-region, uma única requisição de 3MB chegou a levar **80 segundos** no ambiente virtualizado do Docker Desktop — desproporcional a qualquer condição real. Tive que simplificar pra delay fixo sem jitter/perda pra ter números confiáveis.

3. **ghz corta requisições em andamento**: diferente do k6 (que espera terminar iterações em curso), o `ghz` por padrão derruba conexões no exato segundo que a duração do teste acaba — isso inflava artificialmente a taxa de erro do gRPC até eu achar a flag `--duration-stop=wait`.

Nenhum desses bugs invalidou a comparação — mas ignorá-los teria produzido números errados, e eu preferia atrasar a entrega a apresentar um resultado que não resistiria a uma segunda olhada.

## Onde o gRPC perde (e isso importa tanto quanto onde ele ganha)

Uma POC que só mostra vantagem não é confiável. O relatório documenta com a mesma honestidade:

- **Payload binário**: não dá pra inspecionar com um `curl` rápido ou colar no Postman e ler — precisa de ferramenta que entenda o `.proto`.
- **Geração e versionamento de stubs**: o contrato precisa ser mantido em sincronia entre publicador e consumidor, com codegen em cada build.
- **Sem suporte nativo em browser**: SPA chamando o serviço direto precisa de grpc-web ou um proxy no meio.
- **Maturidade de observabilidade**: ferramentas como New Relic ainda têm suporte mais maduro pra REST do que pra gRPC.
- **A troca de gateway** já mencionada — decisão de arquitetura de plataforma, não só do time de aplicação.

## Isso não é teoria isolada

gRPC roda em produção há anos em empresas com times de performance dedicados: **Google** (criador, evolução do framework interno Stubby), **Netflix** (Protobuf/gRPC no design de API interna), **Uber** (gateway de API baseado em gRPC), **Square** (substituiu RPC próprio citando performance como motivador) — além de Cloudflare, Datadog, Salesforce, DoorDash e outras no showcase oficial do projeto. Não é aposta em tecnologia de nicho.

## Onde isso me deixou

Os dados não sustentam uma migração geral — sustentam uma **adoção seletiva**: endpoints internos (serviço-a-serviço, não expostos a browser) com tráfego concorrente relevante e/ou sensibilidade a latência de rede são os candidatos óbvios. Payloads gigantes com baixa concorrência esbarram em gargalo de memória do servidor, não do protocolo — ali vale mais investir em paginação/streaming do que trocar de protocolo.

![Resumo de desempenho e custo](images/final-summary.png)

## Tudo aberto

Código-fonte completo, scripts de benchmark (k6, ghz, simulação de rede), os dois serviços, os gateways, e o relatório completo com todos os gráficos e a metodologia detalhada estão públicos:

🔗 **https://github.com/vinizer4/grpc-x-rest-benchmark**

Se você já passou por essa decisão no seu time, ou discorda de alguma premissa que usei, quero muito ouvir. Comenta aqui ou abre uma issue no repo.

#gRPC #REST #backend #performance #cloud #kotlin #springboot #arquiteturadesoftware
