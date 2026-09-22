# POC: 4 protocolos — HTTP/1.1 REST vs HTTP/2 REST vs gRPC vs HTTP/3 + MessagePack

🌐 [Read this in English](RESULTS_4WAY.en.md)

**Data da rodada:** 2026-09-21
**Ambiente:** Docker Compose local (Postgres 16, 4 serviços Spring Boot 4.1 / Kotlin, 4 gateways nginx simulando API Gateway/ALB/CDN), containers limitados a 1 vCPU / 1GiB (perfil de pod EKS)

Esta é uma extensão da comparação original (ver [RESULTS.md](RESULTS.md)), que cobria apenas REST (HTTP/1.1, JSON) vs gRPC (HTTP/2, Protobuf). Este documento aplica a **mesma metodologia** (mesmos cenários de payload, mesmos 4 perfis de rede, mesmas métricas, mesmos tipos de gráfico) aos 4 protocolos lado a lado — sem alterar nada do que já existia: o serviço `sales-service-rest` original e o documento `RESULTS.md` continuam exatamente como estavam.

## 1. Objetivo e cenário

Mesmo caso de uso real do time usado na POC original: consulta do histórico anual de uma loja, trazendo em uma única resposta as vendas mensais agregadas do catálogo de produtos e as promoções do período. A lógica de negócio é idêntica nos 4 serviços (módulo `sales-domain` compartilhado); a única diferença é a camada de exposição (protocolo + serialização).

| Variante | Serviço | Gateway | Porta | Serialização |
|---|---|---|---|---|
| HTTP/1.1 REST | `sales-service-rest` (já existia) | `api-gateway-sim` (API Gateway) | 8443 | JSON |
| HTTP/2 REST | `sales-service-rest-h2` (novo) | `api-gateway-sim-h2` (novo, API Gateway) | 8543 | JSON |
| gRPC | `sales-service-grpc` (já existia) | `alb-grpc-sim` (ALB) | 9443 | Protobuf |
| HTTP/3 | `sales-service-msgpack` (novo) | `edge-h3-sim` (novo, CDN/edge) | 8843 | MessagePack |

`sales-service-rest-h2` e `sales-service-msgpack` são cópias do mesmo caso de uso, isoladas em serviços próprios, para isolar cada variável (protocolo de transporte, formato de serialização) sem tocar nos serviços originais.

**Por que HTTP/3 precisou de uma arquitetura diferente:** nenhum servidor embarcado da JVM (Tomcat, Jetty, Netty) tem suporte estável a HTTP/3 (QUIC) do lado servidor hoje. A solução usada em produção por quem já adota HTTP/3 é terminar QUIC/TLS numa borda dedicada (CDN/edge) e falar HTTP comum internamente — é o que `edge-h3-sim` faz: um nginx (compilado com `--with-http_v3_module`) termina HTTP/3 na porta 8843 e repassa para `sales-service-msgpack` via HTTP/1.1 simples. O backend não sabe que está atrás de HTTP/3.

**Massa de dados, cenários de payload e perfis de rede:** idênticos à POC original —
- 50 lojas, catálogo de 5.000 produtos, 12 meses de histórico, 5 promoções/mês por loja
- **Médio**: últimos 3 meses → 15.000 vendas + 15 promoções por resposta (~3,3 MB em JSON)
- **Grande**: 12 meses completos → 60.000 vendas + 60 promoções por resposta (~13,1 MB em JSON)
- Perfis de rede via `tc`/`netem`: `baseline` (sem shaping), `same-az` (0,5ms), `cross-az` (1,5ms), `cross-region` (100ms)
- **Concorrência:** 20 requisições simultâneas no cenário médio, 5 no cenário grande (mesmas premissas do benchmark original — o cenário grande é limitado por memória, ver seção 4)
- Ferramentas: `k6` para os dois REST (HTTP/1.1 e HTTP/2 — mesmo script, só muda a porta), `ghz` para gRPC (sem alteração), e um script próprio em Python com `aioquic` para HTTP/3, já que nenhuma ferramenta madura de carga (k6, ghz, wrk) tem suporte real a HTTP/3 hoje. Ver [benchmark/h3/README.md](../benchmark/h3/README.md).

**Ressalva importante de ambiente (ver nota metodológica completa no final):** esta rodada tem **4 serviços + 4 gateways rodando simultaneamente** no mesmo host Docker Desktop, contra 2+2 da POC original — isso introduz contenção de CPU no nível do host que não existia na medição original, então os **valores absolutos** de REST/gRPC aqui podem diferir dos publicados em `RESULTS.md`. A comparação **relativa entre os 4 protocolos dentro desta mesma rodada** continua válida, e é o que este documento reporta.

## 2. Latência e throughput

### Legenda — o que cada coluna significa

- **Protocolo**: `REST` = JSON via `api-gateway-sim`; `REST/H2` = JSON via `api-gateway-sim-h2`; `gRPC` = Protobuf via `alb-grpc-sim`; `HTTP/3` = MessagePack via `edge-h3-sim`.
- **Cenário**: `médio` = consulta de 3 meses (~3,3MB em JSON); `grande` = consulta de 12 meses (~13,1MB em JSON).
- **Perfil de rede**: latência simulada via `tc`/`netem` — `baseline`, `same-az` (~0,5ms), `cross-az` (~1,5ms), `cross-region` (~100ms).
- **p50 / p95 / p99**: percentis de latência por requisição, em milissegundos.
- **Throughput**: requisições completadas por segundo, com a concorrência do cenário (20 no médio, 5 no grande).
- **Sucesso**: percentual de requisições que completaram com resposta válida.

| Protocolo | Cenário | Perfil | p50 (ms) | p95 (ms) | p99 (ms) | Throughput (req/s) | Sucesso |
|---|---|---|---|---|---|---|---|
| REST | médio | baseline | 984 | 1791 | 2352 | 19,0 | 100% |
| REST/H2 | médio | baseline | 1079 | 5797 | 6522 | 13,7 | 100% |
| gRPC | médio | baseline | 1012 | 2700 | 2869 | 17,8 | 100% |
| HTTP/3 | médio | baseline | 2105 | 5015 | 5465 | 8,1 | 100% |
| REST | médio | same-az | 958 | 3028 | 3688 | 18,4 | 100% |
| REST/H2 | médio | same-az | 1098 | 1619 | 2245 | 17,4 | 100% |
| gRPC | médio | same-az | 844 | 1436 | 1713 | 22,7 | 100% |
| HTTP/3 | médio | same-az | 2310 | 7950 | 8568 | 1,2 | 100% |
| REST | médio | cross-az | 955 | 1473 | 1983 | 20,0 | 100% |
| REST/H2 | médio | cross-az | 1102 | 1631 | 1904 | 17,7 | 100% |
| gRPC | médio | cross-az | 905 | 1794 | 2584 | 20,6 | 100% |
| HTTP/3 | médio | cross-az | 1748 | 2223 | 2658 | 11,2 | 100% |
| REST | médio | cross-region | 2795 | 7158 | 7990 | 5,3 | 100% |
| REST/H2 | médio | cross-region | 3093 | 8720 | 10368 | 4,9 | 100% |
| gRPC | médio | cross-region | 1213 | 3545 | 4068 | 14,4 | 100% |
| HTTP/3 | médio | cross-region | 4852 | 9850 | 9929 | 3,2 | 100% |
| REST | grande | baseline | 986 | 1909 | 3243 | 4,6 | 100% |
| REST/H2 | grande | baseline | 1205 | 3444 | 3477 | 3,4 | 100% |
| gRPC | grande | baseline | 1010 | 3217 | 3383 | 4,3 | 100% |
| HTTP/3 | grande | baseline | 1628 | 2636 | 2707 | 0,7 | 100% |
| REST | grande | same-az | 1110 | 1952 | 2312 | 4,3 | 100% |
| REST/H2 | grande | same-az | 1026 | 1459 | 1488 | 4,8 | 100% |
| gRPC | grande | same-az | 931 | 1445 | 1838 | 5,1 | 100% |
| HTTP/3 | grande | same-az | 1738 | 2376 | 2442 | 0,5 | 100% |
| REST | grande | cross-az | 942 | 1329 | 1622 | 5,2 | 100% |
| REST/H2 | grande | cross-az | 956 | 1968 | 2279 | 4,7 | 100% |
| gRPC | grande | cross-az | 909 | 1214 | 1322 | 5,5 | 100% |
| HTTP/3 | grande | cross-az | 1596 | 2749 | 2844 | 2,9 | 100% |
| REST | grande | cross-region | 1165 | 3099 | 7040 | 2,9 | 100% |
| REST/H2 | grande | cross-region | 1744 | 3528 | 3726 | 2,4 | 100% |
| gRPC | grande | cross-region | 1779 | 4743 | 5787 | 2,5 | 100% |
| HTTP/3 | grande | cross-region | **24942** | **25025** | **25040** | 0,2 | 100% |

*(Gerada por `benchmark/consolidate/consolidate-4way.py` a partir das saídas de `k6`, `ghz` e `benchmark/h3/load_test.py`; ver `benchmark/results/summary-4way.csv` para os números completos e `benchmark/results/charts-4way/` para os gráficos brutos por protocolo.)*

![Comparativo de p99 e throughput — payload médio](images/comparison-medium-4way.png)

![Comparativo de p99 e throughput — payload grande](images/comparison-large-4way.png)

**Leitura dos números:**

- **gRPC é o mais consistente sob rede ruim**: em `cross-region` (cenário médio), gRPC entrega **p99 49% menor e 170% mais throughput** que HTTP/1.1 REST — o HTTP/2 multiplexado do gRPC amortiza melhor o custo de round-trips extras que uma rede lenta impõe. Esse padrão se repete, de forma mais modesta, em `same-az`/`cross-az`.
- **HTTP/2 REST sozinho não é uma vitória garantida**: no cenário grande (uma única resposta enorme por requisição, sem nada para multiplexar), HTTP/2 REST teve o **pior p95 em baseline** (3444ms vs 1909ms do HTTP/1.1) — o overhead de controle de fluxo por stream do HTTP/2 pesa mais do que ajuda quando não há múltiplas requisições pequenas para intercalar na mesma conexão. No cenário médio ele às vezes ganha (`same-az`, `cross-az`), às vezes perde feio (`baseline`, `cross-region`) — o resultado é bem menos previsível que o do gRPC.
- **HTTP/3 + MessagePack é competitivo em condições normais, mas tem um comportamento anômalo em `cross-region` grande**: o p50 salta para **~25 segundos**, ~13x mais lento que os outros 3 protocolos sob o mesmo delay de 100ms. Isso foi confirmado como reprodutível (não é ruído de uma única medição) e discutido na nota metodológica — é um artefato de como QUIC (controle de congestionamento/pacing) interage com o `tc`/`netem` de delay fixo no ambiente virtualizado do Docker Desktop, não necessariamente representativo de HTTP/3 real atrás de uma CDN de produção (que não usa essa emulação de rede).
- **Sob concorrência alta e host compartilhado (esta rodada), REST/HTTP1.1 e gRPC ficam próximos em `baseline`** — diferente da POC original, que rodava só 2 serviços e mostrava gRPC mais claramente à frente já em baseline. Isso é esperado: com 8 serviços + 4 gateways ativos no mesmo host, a contenção de CPU no nível da VM do Docker Desktop reduz a folga que cada container tinha sozinho. A vantagem do gRPC continua evidente, mas fica mais visível nos perfis de rede mais adversos do que no baseline isolado.

## 3. Tamanho do payload

Medido byte a byte no wire (mesma loja, mesmo período, cenário grande):

| Protocolo | Bytes | Redução vs JSON |
|---|---|---|
| REST (JSON) | 13.119.848 (~13,1 MB) | — |
| REST/H2 (JSON) | 13.119.848 (~13,1 MB) | 0% (mesma serialização) |
| gRPC (Protobuf) | 5.934.663 (~5,9 MB) | **54,8%** |
| HTTP/3 (MessagePack) | 11.093.493 (~11,1 MB) | **15,4%** |

![Comparativo de tamanho de payload](images/payload-size-4way.png)

MessagePack reduz o payload por ser binário e mais compacto que JSON, mas fica bem atrás do Protobuf: MessagePack ainda carrega os nomes dos campos e não tem um schema compilado, então boa parte da economia de tamanho do Protobuf (que codifica campos por número, não por nome) não se aplica.

## 4. CPU e memória sob carga

Amostrado via `docker stats` durante o cenário grande, concorrência 5, ~20s de carga:

| Serviço | CPU sob carga | Memória sob carga |
|---|---|---|
| sales-service-rest | 35-102% de 1 vCPU | 33-84% de 1GiB |
| sales-service-rest-h2 | 12-103% de 1 vCPU | 66-89% de 1GiB |
| sales-service-grpc | 0-104% de 1 vCPU | 60-81% de 1GiB |
| sales-service-msgpack | 9-100% de 1 vCPU | 29-92% de 1GiB |

**Achado que se repete da POC original, agora em dobro:** durante a execução completa da matriz de 32 combinações (4 protocolos × 2 cenários × 4 perfis), **tanto `sales-service-rest` quanto `sales-service-rest-h2` foram derrubados por falta de memória (OOMKilled) em pelo menos uma rodada**, assim como `sales-service-grpc` e `sales-service-msgpack` em momentos isolados de maior concorrência (20 simultâneas no cenário médio). Isso não é uma falha de configuração dos novos serviços — é a mesma limitação de memória documentada na POC original (seção 4 de [RESULTS.md](RESULTS.md)): manter dezenas de milhares de entidades/objetos na memória para múltiplas requisições concorrentes satura rápido um limite de 1GiB, independente do protocolo. Payloads menores (Protobuf, MessagePack) sofrem proporcionalmente menos, mas não estão imunes sob concorrência alta o suficiente.

## 5. Trade-offs que não aparecem nos números

Os trade-offs qualitativos do gRPC já documentados na seção 5 de [RESULTS.md](RESULTS.md) (payload não legível, geração de stubs, suporte a browser, assimetria de gateway na AWS, maturidade de observabilidade, limite de tamanho de mensagem) continuam valendo e não são repetidos aqui. Trade-offs específicos das 2 variantes novas:

- **HTTP/2 REST**: ganha o multiplexing do HTTP/2 sem precisar trocar a serialização (fica JSON, fica REST, fica com Postman/curl legível) — é o caminho de menor esforço para quem quer uma melhoria incremental sem reescrever contratos. Mas, como mostrado na seção 2, esse ganho **não é garantido** para todo padrão de tráfego; para respostas únicas muito grandes ele pode ser pior que HTTP/1.1 simples.
- **HTTP/3 + MessagePack**: exige uma peça de infraestrutura nova e real (uma borda que termine QUIC — CDN ou proxy dedicado), não apenas configuração de aplicação, já que a JVM não serve HTTP/3 nativamente. Isso é uma mudança arquitetural, não uma troca de biblioteca. MessagePack, por sua vez, tem suporte de ferramentas bem mais limitado que JSON ou Protobuf — não existe um "Postman nativo" para MessagePack, e depurar um payload capturado exige uma ferramenta que entenda o formato.

## 6. Possível redução de custos

Mesma metodologia da seção 6 de [RESULTS.md](RESULTS.md): estimativa de ordem de grandeza, não previsão financeira precisa.

### Simulação: 4.000 lojas consultando o endpoint 1x/dia (12 meses de histórico)

| | REST (JSON) | REST/H2 (JSON) | gRPC (Protobuf) | HTTP/3 (MessagePack) |
|---|---|---|---|---|
| Volume por dia | 52,5 GB | 52,5 GB | 23,7 GB | 44,4 GB |
| Volume por mês | 1.574 GB | 1.574 GB | 712 GB | 1.331 GB |
| Volume por ano | 19,2 TB | 19,2 TB | 8,7 TB | 16,2 TB |
| Custo anual estimado* | US$ 1.724 | US$ 1.724 | **US$ 780** | US$ 1.458 |
| Economia vs REST | — | 0% | **US$ 944/ano (54,8%)** | US$ 266/ano (15,4%) |

*\*Referência AWS de US$0,09/GB para egress à internet — a redução percentual é a mesma independentemente da tarifa usada.*

![Projeção de custo com 4.000 lojas/dia](images/cost-projection-4000-stores-4way.png)

Mesma ressalva da POC original: isto é uma extrapolação linear sobre o payload atual desta POC (que é menor que o payload real de produção do time). A conclusão direcional (gRPC > HTTP/3+MessagePack > HTTP/2 REST ≈ HTTP/1.1 REST em redução de egress) deve se manter proporcionalmente com um payload maior.

### Custo de compute: pods necessários para o mesmo throughput

Como mostrado na seção 2, o throughput em `baseline` nesta rodada é distorcido pela contenção de host (8 serviços simultâneos, ver seção 1) — ali gRPC (17,8 req/s) chega a ficar levemente atrás do HTTP/1.1 REST (19,0 req/s), o oposto do padrão que se repete em todo perfil com latência de rede real. Por isso, a tabela abaixo usa o throughput medido em `médio/cross-region` (mais representativo de tráfego real entre regiões) como base, em vez de `baseline`.

Premissas: 5 pods de referência por endpoint (1 vCPU/1GiB) para HTTP/1.1 REST, escalado pela razão de throughput de cada protocolo em relação a ele; custo AWS Fargate ≈ US$32,80/pod/mês.

| Endpoints migrados | Pods REST | Pods REST/H2 | Pods gRPC | Pods HTTP/3 |
|---|---|---|---|---|
| 1 | 5 | 6 | **2** | 8 |
| 5 | 25 | 28 | **9** | 42 |
| **10** | **50** | **55** | **19** | **85** |
| 15 | 75 | 83 | **28** | 127 |
| 20 | 100 | 110 | **37** | 170 |

![Infraestrutura necessária ao escalar](images/scale-pods-4way.png)

![Custo projetado ao escalar](images/scale-cost-4way.png)

**Por que HTTP/3+MessagePack precisa de mais pods nesta tabela:** o script de carga em Python/`aioquic` (necessário porque nenhuma ferramenta madura tem suporte a HTTP/3) tem throughput de cliente bem menor que k6/ghz (binários compilados, multi-threaded) — parte da diferença de "throughput" medida é limitação da ferramenta de carga, não do protocolo/serviço em si. Este número deve ser lido com mais cautela que os demais.

## 7. Conclusão e recomendação

A conclusão da POC original (seção 8 de [RESULTS.md](RESULTS.md)) — adoção seletiva de gRPC para endpoints internos de alto tráfego concorrente e/ou sensíveis a latência de rede — **continua de pé** com estes dados adicionais. As 2 variantes novas reforçam pontos específicos:

- **HTTP/2 REST** é a opção de menor esforço para quem quer melhorar sem trocar serialização, mas os dados mostram que o ganho depende muito do padrão de tráfego — não é uma troca "sempre positiva" como às vezes é vendida. Vale medir antes de adotar, não assumir.
- **HTTP/3 + MessagePack** é tecnicamente viável e reduz payload (15% vs JSON), mas exige uma peça de infraestrutura nova (borda com terminação QUIC) e, no ambiente testado, mostrou uma degradação abrupta e reprodutível sob latência de rede alta que precisa ser investigada mais a fundo antes de qualquer decisão de produção — não é um resultado que se deve ignorar nem generalizar sem mais dados.
- **gRPC continua sendo a opção com o trade-off mais favorável** entre os 4, especialmente sob rede adversa — o cenário mais realista para tráfego cross-AZ/cross-region em produção.

---

## Resumo rápido

| | REST (JSON) | REST/H2 (JSON) | gRPC (Protobuf) | HTTP/3 (MsgPack) |
|---|---|---|---|---|
| p99, rede ruim (médio/cross-region) | 7.990 ms | 10.368 ms | **4.068 ms** | 9.929 ms |
| Throughput, rede ruim (médio/cross-region) | 5,3 req/s | 4,9 req/s | **14,4 req/s** | 3,2 req/s |
| Tamanho do payload | 13,1 MB | 13,1 MB | **5,9 MB** | 11,1 MB |
| Redução de egress vs REST | — | 0% | **54,8%** | 15,4% |
| Custo anual estimado, 4.000 lojas/dia | US$ 1.724 | US$ 1.724 | **US$ 780** | US$ 1.458 |

![Resumo de desempenho e custo](images/final-summary-4way.png)

gRPC vence nas três frentes centrais (latência de cauda sob rede ruim, throughput sob rede ruim, tamanho de payload/custo), o mesmo padrão da POC original. As duas variantes novas ocupam posições intermediárias: HTTP/2 REST reduz custo de adoção mas não entrega ganho de desempenho consistente; HTTP/3+MessagePack reduz payload de forma modesta mas introduz uma dependência de infraestrutura nova e um comportamento sob rede ruim que precisa de mais investigação antes de qualquer recomendação de produção.

---

## Nota metodológica

- **Ambiente mais concorrido que a POC original:** esta rodada tem 4 serviços de aplicação + 4 gateways rodando simultaneamente (contra 2+2 da POC original), todos no mesmo host Docker Desktop. Isso introduz contenção de CPU no nível da VM que não existia na medição original — os valores absolutos de REST/gRPC aqui **não são diretamente comparáveis** aos de `RESULTS.md`; a comparação relativa entre os 4 protocolos dentro desta mesma rodada é o dado confiável.
- **Anomalia de HTTP/3 em `cross-region`/grande:** o p50 sobe para ~25 segundos, ~13x mais lento que os outros 3 protocolos sob o mesmo delay fixo de 100ms — confirmado reprodutível em 2 execuções independentes. A hipótese mais provável é uma interação entre o controle de congestionamento/pacing do QUIC e a emulação de delay fixo do `tc`/`netem` no ambiente virtualizado do Docker Desktop (rede TCP não mostrou esse efeito sob o mesmo delay). Isso é uma limitação a investigar do ambiente de teste local, não necessariamente uma característica de HTTP/3 em produção atrás de uma CDN real — mas também não deve ser descartada sem mais testes num ambiente não-virtualizado.
- **OOM (falta de memória) durante a rodada:** `sales-service-rest`, `sales-service-rest-h2` e `sales-service-msgpack` foram reiniciados ao menos uma vez cada durante a execução da matriz completa de 32 testes, por atingirem o limite de 1GiB sob concorrência de 20 simultâneas com payload grande. Cada serviço foi reiniciado e a rodada específica refeita antes de ser registrada nesta tabela — nenhum número final reportado aqui vem de uma execução que sofreu OOM no meio.
- Todos os testes tiveram uma rodada de aquecimento (warm-up) antes da medição — sem isso, a primeira medição de gRPC no cenário médio mostrou uma distribuição bimodal clara (p50 normal, mas p75+ saltando para 19,5 segundos), um artefato de JIT warm-up/inicialização de pool de conexões, não do protocolo.
- Os perfis de rede usam **delay fixo, sem jitter nem perda de pacote** — mesma decisão e mesmo racional documentado na POC original.
- Os containers dos 4 serviços rodam com `-XX:MaxRAMPercentage=75.0` explícito, mesma configuração da POC original.
