# POC: 4 protocolos — HTTP/1.1 REST vs HTTP/2 REST vs gRPC vs HTTP/3 + MessagePack

🌐 [Read this in English](RESULTS_4WAY.en.md)

**Data da rodada:** 2026-09-21
**Ambiente:** Docker Compose local (Postgres 16, 4 serviços Spring Boot 4.1 / Kotlin, 4 gateways nginx), containers limitados a 1 vCPU / 1GiB (perfil de pod EKS)

Esta é uma extensão da comparação original (ver [RESULTS.md](RESULTS.md)), que cobria apenas REST (HTTP/1.1, JSON) vs gRPC (HTTP/2, Protobuf). Este documento cobre os 4 protocolos lado a lado, sem alterar nada do que já existia: o serviço `sales-service-rest` original e seus resultados continuam exatamente como estavam.

## 1. O que foi adicionado

| Variante | Serviço | Gateway | Porta | Serialização |
|---|---|---|---|---|
| HTTP/1.1 REST | `sales-service-rest` (já existia) | `api-gateway-sim` | 8443 | JSON |
| HTTP/2 REST | `sales-service-rest-h2` (novo) | `api-gateway-sim-h2` (novo) | 8543 | JSON |
| gRPC | `sales-service-grpc` (já existia) | `alb-grpc-sim` | 9443 | Protobuf |
| HTTP/3 | `sales-service-msgpack` (novo) | `edge-h3-sim` (novo) | 8843 | MessagePack |

`sales-service-rest-h2` e `sales-service-msgpack` são cópias do mesmo caso de uso (histórico anual de loja, mesmo módulo `sales-domain`), isoladas em serviços próprios — o objetivo era isolar cada variável (protocolo de transporte, formato de serialização) sem tocar nos serviços originais.

**Por que HTTP/3 precisou de uma arquitetura diferente:** nenhum servidor embarcado da JVM (Tomcat, Jetty, Netty) tem suporte estável a HTTP/3 (QUIC) do lado servidor hoje. A solução usada em produção por quem já adota HTTP/3 é terminar QUIC/TLS numa borda dedicada (CDN/edge) e falar HTTP comum internamente — é o que `edge-h3-sim` faz: um nginx (compilado com `--with-http_v3_module`) termina HTTP/3 na porta 8843 e repassa para `sales-service-msgpack` via HTTP/1.1 simples. O backend não sabe que está atrás de HTTP/3.

## 2. Metodologia

- Mesma massa de dados e mesmo cenário "grande" (12 meses, 60.000 vendas + 60 promoções, loja sorteada entre 50) usados no benchmark original.
- **Concorrência: 5 conexões simultâneas, 20s de duração, em todos os 4 protocolos.** Esse número não foi arbitrário — é o resultado de um problema real encontrado durante os testes (seção 4): com o payload grande (~13MB por resposta) e o limite de 1GiB por container (mesmo perfil de pod EKS usado no benchmark original), concorrências maiores de 10-20 derrubavam os serviços REST por falta de memória. 5 foi o ponto em que todos os 4 protocolos completaram os testes sem erro.
- Ferramentas: `k6` para os dois REST (HTTP/1.1 e HTTP/2 — mesmo script, só muda a porta), `ghz` para gRPC (sem alteração), e um script próprio em Python com `aioquic` para HTTP/3, já que nenhuma ferramenta madura de carga (k6, ghz, wrk) tem suporte real a HTTP/3 hoje. Ver [benchmark/h3/README.md](../benchmark/h3/README.md).
- **Ressalva de ambiente:** o teste de HTTP/3 não roda a partir do host macOS — o Docker Desktop para macOS não repassa de forma confiável os pacotes UDP do handshake QUIC entre o host e a VM interna, mesmo com a configuração de nginx/TLS/QUIC correta (confirmado: o mesmo handshake funciona instantaneamente container-a-container, dentro da rede do Compose). Por isso o script de carga roda como um container anexado à rede do Compose, não do host. Isso é uma limitação do ambiente de desenvolvimento local, não da arquitetura proposta — em produção, atrás de uma CDN real, essa camada host↔VM não existe.

## 3. Latência e throughput

| Protocolo | p50 (ms) | p95 (ms) | p99 (ms) | Throughput (req/s) | Sucesso |
|---|---|---|---|---|---|
| gRPC (Protobuf) | 1009 | 3216 | 3526 | 4,17 | 100% |
| HTTP/1.1 REST (JSON) | 1186 | 3457 | 3578 | 3,29 | 100% |
| HTTP/3 (MessagePack) | 1841 | 4772 | 4823 | 2,28 | 100% |
| HTTP/2 REST (JSON) | 1471 | 10367 | 10375 | 1,76 | 100% |

**O resultado mais contraintuitivo: HTTP/2 REST teve o pior p95/p99 dos quatro**, apesar de HTTP/2 ser geralmente vendido como estritamente melhor que HTTP/1.1. A explicação provável: o ganho do HTTP/2 vem de multiplexar várias requisições pequenas na mesma conexão — aqui cada requisição já é uma única resposta enorme (~13MB) por conexão, então não há nada para multiplexar, e o overhead de controle de fluxo por stream do HTTP/2 (janelas de flow control, framing) passa a pesar mais do que ajudar num cenário de "poucas requisições grandes" como este. Isso é uma lição real da POC: a vantagem do HTTP/2 depende muito do padrão de tráfego, e um payload gigante por resposta não é o caso em que ele brilha.

gRPC segue na frente por combinar HTTP/2 com o payload mais leve (Protobuf) — a serialização menor parece compensar o mesmo overhead de streams que penalizou o REST HTTP/2.

## 4. Tamanho do payload

| Protocolo | Bytes | Redução vs JSON |
|---|---|---|
| REST (JSON) | 13.119.848 | — |
| gRPC (Protobuf) | 5.934.663 | 54,8% |
| HTTP/3 (MessagePack) | 11.093.493 | 15,4% |

MessagePack reduz o payload por ser binário e mais compacto que JSON, mas fica longe da redução do Protobuf — MessagePack preserva nomes de campos e não tem um schema compilado como o Protobuf, então o ganho de tamanho é bem mais modesto.

## 5. Limite de memória: um achado relevante, não um bug

Durante os testes, tanto `sales-service-rest` (HTTP/1.1, o serviço original) quanto `sales-service-rest-h2` (HTTP/2) foram **derrubados por falta de memória (OOMKilled)** ao rodar com concorrência de 10-20 conexões simultâneas — mesmo limite de 1GiB usado desde o benchmark original. `sales-service-grpc` e `sales-service-msgpack` aguentaram concorrências maiores sem cair.

Isso não é um problema de configuração dos novos serviços — é uma consequência direta de servir respostas de ~13MB via JSON: cada requisição simultânea mantém uma cópia grande da resposta serializada em memória (Jackson materializa a árvore inteira antes de escrever), e múltiplas dessas em paralelo somam rápido contra um limite de 1GiB calibrado para um pod típico. Payloads menores (Protobuf, MessagePack) sofrem proporcionalmente menos.

**Implicação prática para quem for decidir entre REST e gRPC/protocolos binários com payloads grandes:** o dimensionamento de memória do pod não pode ser o mesmo independente do protocolo — REST com JSON precisa de mais headroom de memória para o mesmo volume de tráfego simultâneo, o que se traduz diretamente em custo de infraestrutura, não só em latência.

## 6. Resumo

| Aspecto | Vencedor |
|---|---|
| Latência (p50) | gRPC, mas HTTP/1.1 REST muito próximo |
| Latência de cauda (p95/p99) | gRPC |
| Tamanho de payload | gRPC (Protobuf) |
| Throughput sob memória limitada | gRPC, depois HTTP/1.1 REST |
| Resiliência a alta concorrência (sem OOM) | gRPC e HTTP/3+MessagePack |
| Pior desempenho no cenário testado | HTTP/2 REST (payload único grande, sem ganho de multiplexação) |

Para este caso de uso específico — uma única resposta grande por requisição — gRPC continua sendo a escolha técnica mais forte, consistente com a conclusão do benchmark original. A adição destas 3 variantes reforça esse resultado em vez de contradizê-lo, e traz um alerta útil: HTTP/2 (sozinho, sem mudar a serialização) não é uma vitória garantida para todo padrão de tráfego.

---

*Como reproduzir: ver [benchmark/h3/README.md](../benchmark/h3/README.md) para o script de HTTP/3, e os scripts existentes em `benchmark/k6/` e `benchmark/ghz/` para os demais. `python3 benchmark/consolidate/consolidate-4way.py` gera esta tabela e os gráficos a partir dos resultados brutos.*
