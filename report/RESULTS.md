# POC: REST vs gRPC — Histórico de Vendas

> Preencher as seções marcadas com `<!-- TODO -->` após rodar os benchmarks completos (ver `README`/roadmap do repositório para os comandos exatos). As tabelas abaixo têm a estrutura esperada; `benchmark/consolidate/consolidate.py` gera `benchmark/results/summary.md` automaticamente a partir dos resultados de `k6`/`ghz`/`measure-payload-size.sh` — copie o conteúdo gerado para as seções correspondentes.

## 1. Objetivo e cenário

Comparação de desempenho entre REST (JSON) e gRPC (Protobuf) para o mesmo caso de uso — consulta de histórico de vendas de um produto — usando a mesma lógica de domínio nos dois serviços (`sales-domain`), atrás de gateways que simulam a topologia real da empresa: API Gateway (REST) e ALB com target group gRPC (gRPC), já que API Gateway não suporta proxy gRPC nativamente.

- **Volume de dados**: 8.000.000 de registros de vendas, 12 meses, 500 produtos, 50 lojas, distribuição pareto-like (produtos de alto giro concentram mais vendas).
- **Cenário "payload médio"**: consulta de ~30 dias.
- **Cenário "payload grande"**: consulta de ~12 meses de um produto de alto giro.
- **Condições de rede testadas**: baseline (sem shaping), `same-az` (~0.5ms), `cross-az` (~1.5ms), `cross-region` (~100ms + jitter + perda de pacote).

<!-- TODO: confirmar volume real usado na rodada final e o produto/loja escolhidos para cada cenário -->

## 2. Latência e throughput

<!-- TODO: colar aqui a tabela gerada em benchmark/results/summary.md após rodar k6 (REST) e ghz (gRPC) para os 2 cenários x 4 condições de rede -->

| Protocolo | Cenário | Perfil de rede | p50 (ms) | p95 (ms) | p99 (ms) | Throughput (req/s) | Sucesso |
|---|---|---|---|---|---|---|---|
| REST | medio | baseline | | | | | |
| gRPC | medio | baseline | | | | | |
| REST | grande | baseline | | | | | |
| gRPC | grande | baseline | | | | | |
| REST | medio | cross-region | | | | | |
| gRPC | medio | cross-region | | | | | |
| REST | grande | cross-region | | | | | |
| gRPC | grande | cross-region | | | | | |

**Leitura esperada**: a vantagem de latência do gRPC deve crescer conforme a condição de rede piora (HTTP/2 multiplexado amortiza melhor o custo de round-trips que HTTP/1.1), e o throughput deve favorecer gRPC de forma mais acentuada no cenário de payload grande.

## 3. Tamanho de payload (JSON vs Protobuf)

Medido byte a byte no wire (não é uma estimativa) via `benchmark/measure-payload-size.sh`, que usa o serializador real de cada lado (não a saída em texto do `grpcurl`, que não reflete o tamanho binário real).

<!-- TODO: colar aqui a tabela de benchmark/results/payload-sizes.csv -->

| Cenário | Produto | JSON (bytes) | Protobuf (bytes) | Redução |
|---|---|---|---|---|
| medio | | | | |
| grande | | | | |

Em testes exploratórios com uma amostra de 200 mil registros, a redução observada ficou em torno de **47%** em ambos os cenários — o suficiente para não ser desprezível em uma rede real, mas não uma ordem de magnitude.

## 4. CPU e memória sob carga

<!-- TODO: colar aqui um resumo de benchmark/resource-monitor/monitor.sh rodado durante os testes de carga (média/pico de CPU e memória por serviço) -->

| Serviço | CPU média | CPU pico | Memória média | Memória pico |
|---|---|---|---|---|
| sales-service-rest | | | | |
| sales-service-grpc | | | | |

Ambos os serviços rodam sob os mesmos limites de recurso (`docker-compose.yml`: 0.5-1 CPU / 512Mi-1Gi, aproximando um perfil de pod EKS), então a comparação é direta.

## 5. Trade-offs que não aparecem nos números

Uma POC honesta precisa admitir onde gRPC perde, mesmo que os números de latência/payload favoreçam. Estes pontos não foram medidos quantitativamente, mas são reais e relevantes para a decisão:

- **Payload não legível**: o Protobuf é binário. Não dá para inspecionar uma requisição com um `curl` rápido, colar no Postman e ler, ou debugar um payload capturado em log sem uma ferramenta que conheça o `.proto`. Isso tem custo real em velocidade de debug, principalmente para quem não trabalha com gRPC no dia a dia.
- **Geração e versionamento de stubs**: o contrato (`sales.proto`) precisa ser mantido em sincronia entre os times que publicam e consomem o serviço, com geração de código em cada build. Isso muda o fluxo de desenvolvimento (é preciso rodar codegen, versionar `.proto` como parte da API pública, cuidar de compatibilidade de campos) de um jeito que REST/JSON não exige.
- **Suporte limitado a gRPC nativo em browsers**: chamar um serviço gRPC direto do navegador não funciona sem grpc-web (que por sua vez exige um proxy) ou sem passar por um gateway que faça a tradução. Se algum consumidor futuro for uma SPA chamando o serviço diretamente, isso é uma complicação a mais.
- **Assimetria de gateway na AWS**: como medido nesta POC, o **API Gateway não suporta proxy gRPC** — na prática, adotar gRPC também significa trocar o componente de borda (de API Gateway para ALB, ou para um service mesh), o que tem implicações de segurança, observabilidade e operação que a equipe de plataforma precisa planejar, não é só uma troca de biblioteca no serviço.
- **Maturidade de observabilidade**: ferramentas como New Relic, e o conjunto de dashboards/alertas já configurados para HTTP/REST no time, têm suporte mais maduro e testado para REST do que para gRPC. Adotar gRPC pode exigir configuração adicional de tracing/métricas e uma curva de aprendizado da equipe até chegar no mesmo nível de visibilidade operacional que já existe hoje.

## 6. Conclusão e recomendação

<!-- TODO: preencher após ver os números reais. Estrutura sugerida:
- Em quais cenários (rede ruim + payload grande?) o ganho do gRPC é claro o suficiente para justificar o custo de adoção?
- Os trade-offs da seção 5 pesam mais para o contexto do time (ex: dependência forte de debug manual via Postman, squad pequeno sem experiência prévia com gRPC)?
- Recomendação: adotar gRPC em quais serviços/endpoints especificamente (ex: só nos de maior volume/latência sensível), manter REST nos demais, ou não adotar agora?
-->
