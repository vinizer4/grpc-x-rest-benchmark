# POC: REST vs gRPC — Histórico Anual de Loja

**Data da rodada:** 2026-09-20
**Ambiente:** Docker Compose local (Postgres 16, 2 serviços Spring Boot 4.1 / Kotlin, 2 gateways nginx simulando API Gateway e ALB), containers limitados a 1 vCPU / 1GiB (perfil de pod EKS)

## 1. Objetivo e cenário

Comparação de desempenho entre REST (JSON) e gRPC (Protobuf) para o **mesmo caso de uso real do time**: consulta do histórico anual de uma loja, trazendo em uma única resposta as vendas mensais agregadas de todo o catálogo de produtos da loja e as promoções do período — o mesmo formato que hoje é produzido pelo ETL (Glue), não dados de transação individual.

A lógica de negócio é idêntica nos dois serviços (módulo `sales-domain` compartilhado); a única diferença é a camada de exposição. Os dois serviços rodam atrás de gateways que simulam a topologia real da AWS: **API Gateway** na frente do REST (com autenticação por API key e rate limiting) e **ALB** na frente do gRPC (sem essas camadas, porque API Gateway não suporta proxy gRPC nativamente — essa assimetria é intencional e discutida na seção 5).

**Massa de dados:**
- 50 lojas, catálogo compartilhado de 5.000 produtos, 12 meses de histórico, 5 promoções/mês por loja
- Total: 3.000.000 de linhas de vendas mensais agregadas + 3.000 promoções
- Cada requisição de benchmark sorteia uma loja aleatória entre as 50 (round-robin no gRPC via `ghz`, aleatório no REST via `k6`), simulando tráfego de produção batendo em lojas diferentes, não sempre a mesma

**Cenários de payload** (variando o período consultado da mesma loja):
- **Médio**: últimos 3 meses → 15.000 vendas + 15 promoções por resposta (~3,3 MB em JSON)
- **Grande**: 12 meses completos → 60.000 vendas + 60 promoções por resposta (~13,1 MB em JSON)

**Condições de rede testadas** (via `tc`/`netem` nos gateways, delay fixo sem jitter/perda — ver nota metodológica no final): `baseline` (sem shaping), `same-az` (0,5ms), `cross-az` (1,5ms), `cross-region` (100ms)

**Concorrência:** 20 requisições simultâneas no cenário médio, 5 no cenário grande — o cenário grande é limitado por memória (ver seção 4), então uma concorrência mais alta não é realista sem aumentar os recursos do pod.

## 2. Latência e throughput

| Protocolo | Cenário | Perfil de rede | p50 (ms) | p95 (ms) | p99 (ms) | Throughput (req/s) | Sucesso |
|---|---|---|---|---|---|---|---|
| REST | médio | baseline | 1128 | 1756 | 2153 | 17,2 | 100% |
| gRPC | médio | baseline | 839 | 1517 | 2008 | 22,2 | 100% |
| REST | médio | same-az | 968 | 1407 | 1738 | 20,5 | 100% |
| gRPC | médio | same-az | 917 | 1439 | 1794 | 20,6 | 100% |
| REST | médio | cross-az | 1026 | 1632 | 1844 | 19,1 | 100% |
| gRPC | médio | cross-az | 793 | 1282 | 1505 | 24,0 | 100% |
| REST | médio | cross-region | 1876 | 5685 | **11034** | 7,0 | 100% |
| gRPC | médio | cross-region | 1470 | 2494 | **3172** | 12,5 | 100% |
| REST | grande | baseline | 939 | 1316 | 1633 | 5,1 | 100% |
| gRPC | grande | baseline | 785 | 1155 | 1564 | 6,2 | 100% |
| REST | grande | same-az | 941 | 1534 | 1726 | 5,2 | 100% |
| gRPC | grande | same-az | 807 | 1233 | 1785 | 5,8 | 100% |
| REST | grande | cross-az | 932 | 1463 | 1822 | 5,1 | 100% |
| gRPC | grande | cross-az | 840 | 1377 | 1817 | 5,7 | 100% |
| REST | grande | cross-region | 1194 | 3416 | 4928 | 3,0 | 100% |
| gRPC | grande | cross-region | 1472 | 3251 | 3959 | 3,0 | 100% |

*(Gerada automaticamente por `benchmark/consolidate/consolidate.py` a partir das saídas de `k6` e `ghz`; ver `benchmark/results/summary-latency.csv` para os números completos e `benchmark/results/charts/` para os gráficos por cenário.)*

**Leitura dos números — cenário médio (o mais representativo do padrão real de tráfego, com concorrência de 20):**
- gRPC entrega **26% menos latência p50** e **29% mais throughput** que REST já na rede local (baseline), sem nenhuma condição adversa.
- O resultado mais forte aparece em **cross-region**: o **p99 do REST explode para 11 segundos**, enquanto o gRPC se mantém em 3,2 segundos — uma redução de **71% na cauda de latência**. O throughput do gRPC também é **79% maior** nessa condição. Isso acontece porque o HTTP/2 multiplexado do gRPC amortiza melhor o custo de round-trips extras que uma rede mais lenta impõe, enquanto o REST/HTTP1.1 paga esse custo de forma mais direta por conexão.

**Leitura dos números — cenário grande (payload de 13MB, concorrência de 5, limitado por memória):**
- A vantagem do gRPC é mais modesta e menos consistente: **10-16% de latência p50 menor** e **11-21% mais throughput** em baseline/same-az/cross-az.
- Em cross-region, o resultado inverte no p50 (REST levemente mais rápido), mas o gRPC ainda vence no p99 (~20% menor). Com baixa concorrência (5) e payloads muito grandes, o gargalo passa a ser dominado pelo tempo de serialização/hidratação de 60 mil registros no heap da JVM, que é praticamente igual nos dois lados — a vantagem de protocolo fica diluída.

**Conclusão desta seção:** o ganho do gRPC é claro e crescente conforme a concorrência aumenta e a rede piora — exatamente o cenário de uma API de produção real com múltiplos clientes simultâneos e latência de rede não-trivial. Para payloads muito grandes com baixa concorrência, o ganho ainda existe mas é menor, porque o gargalo deixa de ser rede/serialização e passa a ser processamento de dados no servidor.

![Comparativo de latência p99 e throughput — payload médio](images/comparison-medium.png)

![Comparativo de latência p99 e throughput — payload grande](images/comparison-large.png)

## 3. Tamanho de payload (JSON vs Protobuf)

Medido byte a byte no wire (não é uma estimativa) via `benchmark/measure-payload-size.sh`, que usa o serializador real de cada lado — importante porque `grpcurl` sozinho mostra a resposta gRPC re-renderizada em JSON, não o tamanho binário real transmitido.

| Cenário | Loja | JSON (bytes) | Protobuf (bytes) | Redução |
|---|---|---|---|---|
| médio (3 meses) | 1 | 3.280.037 (~3,3 MB) | 1.483.673 (~1,5 MB) | **54,8%** |
| grande (12 meses) | 1 | 13.119.848 (~13,1 MB) | 5.934.663 (~5,9 MB) | **54,8%** |

A redução é praticamente idêntica nos dois cenários (~55%), o que faz sentido: a estrutura do payload (muitos campos string repetidos por registro — nome do produto, nome da loja, categoria, região) é a mesma, só a quantidade de registros muda.

![Comparativo de tamanho de payload JSON vs Protobuf](images/payload-size.png)

**Efeito direto observado no tráfego de rede durante a carga:** monitorando `docker stats` durante os testes de carga do cenário grande com a mesma concorrência (5 requisições simultâneas, ~20s), o **REST transmitiu ~1,5 GB de saída (egress)** contra **~0,7 GB do gRPC** — uma redução de **53%** no volume de rede, consistente com a redução de payload medida.

## 4. CPU e memória sob carga

| Serviço | CPU sob carga (cenário grande, concorrência 5) | Memória sob carga | Egress de rede (~20s de carga) |
|---|---|---|---|
| sales-service-rest | ~100-106% de 1 vCPU (saturado) | 92-96% de 1GiB (quase no limite) | ~1,5 GB |
| sales-service-grpc | ~65-101% de 1 vCPU (satura ao longo da carga) | 87-96% de 1GiB (quase no limite) | ~0,7 GB |

**Achado importante, independente do protocolo:** com o payload de 12 meses (60 mil vendas + 60 promoções, ~13MB), **ambos os serviços saturam a CPU e chegam perto do limite de memória de 1GiB já com apenas 5 requisições simultâneas**. Isso não é uma limitação do REST nem do gRPC — é o resultado de manter 60 mil entidades JPA + DTOs/objetos protobuf simultaneamente na memória para várias requisições concorrentes, com apenas 1 vCPU/1GiB alocados ao pod.

Isso é um ponto de atenção operacional independente da escolha REST vs gRPC: **um endpoint que devolve o catálogo completo de uma loja em uma única resposta vai precisar de mais memória por pod, paginação, ou streaming se o volume de tráfego concorrente for alto em produção** — trocar de protocolo sozinho não resolve esse gargalo de memória, embora o gRPC ajude a aliviar a pressão de rede.

## 5. Trade-offs que não aparecem nos números

Uma POC honesta precisa admitir onde gRPC perde, mesmo que os números de latência/payload favoreçam. Estes pontos não foram medidos quantitativamente, mas são reais e relevantes para a decisão:

- **Payload não legível**: o Protobuf é binário. Não dá para inspecionar uma requisição com um `curl` rápido, colar no Postman e ler, ou debugar um payload capturado em log sem uma ferramenta que conheça o `.proto`. Isso tem custo real em velocidade de debug, principalmente para quem não trabalha com gRPC no dia a dia.
- **Geração e versionamento de stubs**: o contrato (`sales.proto`) precisa ser mantido em sincronia entre os times que publicam e consomem o serviço, com geração de código em cada build. Isso muda o fluxo de desenvolvimento (é preciso rodar codegen, versionar `.proto` como parte da API pública, cuidar de compatibilidade de campos) de um jeito que REST/JSON não exige.
- **Suporte limitado a gRPC nativo em browsers**: chamar um serviço gRPC direto do navegador não funciona sem grpc-web (que por sua vez exige um proxy) ou sem passar por um gateway que faça a tradução. Se algum consumidor futuro for uma SPA chamando o serviço diretamente, isso é uma complicação a mais.
- **Assimetria de gateway na AWS**: como replicado nesta POC, o **API Gateway não suporta proxy gRPC** — na prática, adotar gRPC também significa trocar o componente de borda (de API Gateway para ALB, ou para um service mesh), o que tem implicações de segurança, observabilidade e operação que a equipe de plataforma precisa planejar. Não é só uma troca de biblioteca no serviço.
- **Maturidade de observabilidade**: ferramentas como New Relic, e o conjunto de dashboards/alertas já configurados para HTTP/REST no time, têm suporte mais maduro e testado para REST do que para gRPC. Adotar gRPC pode exigir configuração adicional de tracing/métricas e uma curva de aprendizado da equipe até chegar no mesmo nível de visibilidade operacional que já existe hoje.
- **Limite de tamanho de mensagem**: o gRPC tem um limite padrão de 4MB por mensagem (tivemos que aumentar explicitamente para 20MB nesta POC para caber a resposta de 12 meses). É mais uma configuração que a equipe precisa conhecer e ajustar deliberadamente, algo que o REST não impõe por padrão.

## 6. Possível redução de custos

Esta é uma estimativa de ordem de grandeza baseada nos números medidos, não uma previsão financeira precisa — os valores reais dependem do volume real de tráfego do endpoint em produção.

**Transferência de dados (egress):** a redução de ~55% no tamanho do payload se traduz diretamente em ~55% menos bytes trafegados pela rede. Isso importa em dois pontos que a AWS cobra:
- **Egress para a internet** (cliente externo consumindo a API): referência pública AWS ~US$ 0,09/GB (após os primeiros 100GB/mês gratuitos).
- **Tráfego cross-AZ/cross-region** entre serviços internos: referência pública AWS ~US$ 0,01-0,02/GB.

Exemplo ilustrativo: se este endpoint específico (histórico anual de loja) transferir hoje, digamos, **1TB/mês** de payload de resposta, uma redução de 55% economizaria aproximadamente:
- ~US$ 49/mês se for majoritariamente egress para internet (1TB × 0,55 × US$0,09/GB ≈ US$50)
- ~US$ 6-11/mês se for majoritariamente tráfego interno cross-AZ/region

Para times com volumes maiores (dezenas de TB/mês em endpoints de alto tráfego), esse número escala linearmente e passa a ser uma economia relevante — mas para um único endpoint de baixo/médio volume, o efeito no custo de infraestrutura provavelmente é pequeno em termos absolutos.

**Custo de compute (menos claro):** no cenário médio (mais representativo do tráfego real), o gRPC sustentou **20-29% mais throughput** com a mesma CPU/memória — em tese, isso poderia permitir atender a mesma carga com menos réplicas do serviço. No cenário grande, porém, os dois protocolos saturam igualmente CPU e memória (seção 4), então não há economia de compute nesse caso — o gargalo lá é o volume de dados por resposta, não o protocolo.

**Recomendação de leitura:** o ganho de custo mais defensável e imediato desta POC é a **redução de egress**, não a redução de compute. Antes de projetar economia, vale medir o volume real de tráfego mensal deste endpoint específico e aplicar a redução de ~55% sobre esse número real.

## 7. Conclusão e recomendação

**Onde o gRPC claramente compensa:**
- Endpoints com tráfego concorrente relevante (dezenas de requisições simultâneas) — o ganho de throughput (+20-29%) e a robustez de latência sob rede ruim (p99 71% menor em cross-region) são diferenças que os usuários finais sentiriam diretamente.
- Cenários com clientes em regiões/AZs diferentes do serviço — quanto pior a rede, maior a vantagem observada do gRPC.
- Onde o volume de tráfego (egress) já é grande o suficiente para o ~55% de redução de payload gerar economia visível na fatura de nuvem.

**Onde o ganho é menor ou o trade-off pesa mais:**
- Endpoints de baixíssima concorrência com payloads muito grandes (o gargalo de memória/CPU do servidor domina, não o protocolo) — nesse caso, vale mais investir em paginação/streaming do que trocar de protocolo.
- Se o time depende fortemente de inspeção manual de payload (Postman, curl, logs) no dia a dia — o custo de debug do binário é real e constante, enquanto o ganho de performance é intermitente (só aparece sob carga/rede ruim).
- Se ainda não há experiência prévia com gRPC no time e nas ferramentas de observabilidade (New Relic) — a curva de aprendizado e o trabalho de habilitar tracing/métricas equivalentes ao que já existe para REST é um custo de adoção não-trivial.
- A troca do componente de borda (API Gateway → ALB) para suportar gRPC é uma decisão de arquitetura de plataforma, não só do time de aplicação — precisa ser alinhada previamente.

**Recomendação:** os dados suportam uma adoção **seletiva**, não uma migração geral. Vale considerar gRPC especificamente para os endpoints internos (serviço-a-serviço, não expostos a browser) de maior volume de chamadas concorrentes e/ou maior sensibilidade a latência de rede — não para toda a superfície de API do time. Antes de migrar algo em produção, validar: (1) o volume real de tráfego do endpoint candidato, para confirmar se a economia de egress é significativa; (2) se a equipe de plataforma já tem (ou está dispostas a montar) o caminho ALB/observabilidade para gRPC.

---

## Nota metodológica

- Os perfis de rede usam **delay fixo, sem jitter nem perda de pacote**. Testes iniciais com jitter (`delay 100ms 20ms`) e perda simulada (`loss 0.05%`) causaram travamentos de retransmissão TCP patológicos no ambiente virtualizado do Docker Desktop (uma única requisição de 3MB chegou a levar 80 segundos), desproporcionais ao que uma condição real de cross-region causaria. O delay fixo ainda captura o efeito de latência por round-trip que os perfis existem para demonstrar, sem esse artefato.
- Os containers dos dois serviços rodam com `-XX:MaxRAMPercentage=75.0` explícito — o padrão da JVM (25% do limite do container) deixaria heap insuficiente (~256MB de um limite de 1GiB) para este endpoint, causando `OutOfMemoryError` em ambos os serviços independentemente do protocolo. Isso é uma configuração de produção real que qualquer pod Java em Kubernetes deveria ter, não um ajuste especial para favorecer um lado da comparação.
- Todos os testes tiveram uma rodada de aquecimento (warm-up) antes da medição, para evitar que o custo de JIT warm-up/inicialização de pool de conexões distorça os primeiros números.
