# Observabilidade: Micrometer instrumenta, OTLP transporta, Collector é a única saída

Dentro das aplicações Spring, a API preferencial de instrumentação é Micrometer
Observation/Tracing. OpenTelemetry é o padrão de propagação e interoperabilidade, OTLP é o protocolo
de saída, e o OpenTelemetry Collector é a fronteira oficial de telemetria: Spring Boot → Micrometer
→ OTLP → Collector → Tempo, Prometheus e Loki → Grafana. As aplicações não conhecem os backends, que
entram localmente por um profile `observabilidade`.

O domínio não chama `MeterRegistry`, `Tracer` nem qualquer API de observabilidade. Instrumentação é
preocupação de aplicação e adapters, e a regra de dependência não abre exceção para telemetria.

## Trace não é o ciclo de vida do negócio

HTTP síncrono propaga W3C Trace Context e continua o trace — `bff-gerente` → `servico-credito` →
`servico-carteira-clientes` aparece como uma árvore só. Fronteira assíncrona durável, não: o consumo
de uma mensagem **inicia uma nova execução observável e mantém Span Link para o contexto produtor**.
Manter artificialmente um único trace aberto durante o ciclo de negócio produziria spans de dias,
inúteis para a ferramenta, e quebraria diante de atraso, retry, redelivery, múltiplos consumidores e
replay.

Spring Batch tem observabilidade própria: trace por `JobExecution`, spans por `StepExecution` e
métricas de execução do job. O batch noturno disparado por scheduler simplesmente começa um trace
novo; se nascer de uma operação anterior identificável, mantém Span Link para ela.

Daí a separação de quatro conceitos que não devem ser fundidos: `traceId` é execução técnica;
`correlationId` é a jornada de negócio, que atravessa HTTP, Outbox, RabbitMQ, Kafka, callback e
reconciliação por horas ou dias; `causationId` é a mensagem imediatamente anterior que causou esta; e
`eventId`/`messageId` é a identidade da mensagem. Auditoria pergunta o que causou uma decisão; SRE
pergunta onde a chamada gastou quatro segundos.

## Logs

Logs são estruturados em JSON, carregando quando disponíveis `traceId`, `spanId`, `correlationId`,
serviço, ambiente, nível e timestamp, e saem pelo Collector. Observabilidade não depende de regex
sobre log textual.

CPF completo, número completo de conta, tokens, credenciais, payloads completos do Core e informação
financeira desnecessária ao diagnóstico não vão para log, salvo representação explicitamente
sanitizada.

## Métrica operacional não é reporting

Micrometer expõe observabilidade operacional do negócio — `decisoes_credito_total{resultado,origem}`,
`efetivacoes_limite_total{resultado}`, tempo de permanência em `AGUARDANDO_EFETIVACAO`, tempo de
`AvaliacaoRisco`. Labels têm cardinalidade controlada: `resultado`, `origem` e `versaoPolitica` são
aceitáveis; `clienteId`, `cpf`, `contaId`, `solicitacaoId`, `protocoloCore` e `correlationId` não
entram em série temporal — pertencem a logs e traces.

Prometheus **não é system of record de negócio**. "Quantas decisões foram aprovadas em agosto" e
"quantas solicitações usaram a versão 17 da política" são perguntas auditáveis, e a resposta deriva
de fatos duráveis — eventos persistidos, projeção dedicada ou dados de auditoria —, nunca de um
contador. Grafana consulta os backends de observabilidade e jamais os bancos dos serviços
(ADR-0014).

## Consequências

Trocar Tempo, Prometheus ou Loki é configuração do Collector, e não mudança em código de aplicação
ou em instrumentação.

Acrescentar um label de métrica passa a ser decisão de projeto, sujeita à pergunta de cardinalidade,
e não conveniência de quem está instrumentando.
