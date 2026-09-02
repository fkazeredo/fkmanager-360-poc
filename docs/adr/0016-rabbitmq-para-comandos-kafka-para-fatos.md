# RabbitMQ carrega comandos, Kafka carrega fatos

O critério não é cardinalidade de destinatário — RabbitMQ também faz publish/subscribe e um tópico
Kafka pode ter um único consumer group. O critério é a semântica da mensagem e as propriedades de
entrega desejadas.

**RabbitMQ para intenção**: trabalho que precisa ser executado, com destinatário funcional
conhecido, competing consumers, ack explícito, retry limitado com backoff e DLQ. `AvaliarRisco` é o
caso — `Credito` precisa que uma `AvaliacaoRisco` aconteça, e comandos são nomeados no imperativo.
Filas duráveis relevantes são quorum queues, a publicação usa publisher confirms, e o consumo só
confirma depois do processamento durável; `nack` com requeue indefinido não é política de erro.

**Kafka para fato ocorrido** que merece existir como stream durável: `AvaliacaoRiscoConcluida`,
`DecisaoCreditoRegistrada`, `LimiteEfetivado`, `MovimentacaoImportada`. Eventos são nomeados no
passado, produtores desconhecem consumidores, e retenção, replay e novos consumer groups são
propriedades desejadas, não efeitos colaterais.

Por isso `Risco` **não responde a `Credito`**: ele publica que algo aconteceu dentro do seu próprio
contexto, `Credito` decide que tem interesse nesse fato, e amanhã auditoria liga o seu próprio
consumidor sem que `Risco` mude. Uma resposta RPC devolveria a `Risco` conhecimento sobre quem o
consome, além de amarrar a duração da avaliação à duração de uma chamada.

## Outbox único por serviço

ADR-0009 justificou o Outbox pela regra geral, e ela vale aqui: quando uma decisão persistida precisa
gerar mensagem externa, a persistência e o registro da mensagem ocorrem na mesma transação.
`Credito` grava o estado e o comando `AvaliarRisco` atomicamente; `Risco` grava o
`ResultadoAvaliacaoRisco` e o evento de conclusão atomicamente. É **um** Outbox por serviço, com o
destino como metadado — não um Outbox por transporte.

## Contrato da mensagem

Eventos inter-contexto usam um envelope técnico comum — `eventId`, `eventType`, `eventVersion`,
`occurredAt`, `producer`, `aggregateType`, `aggregateId`, `correlationId`, `causationId`,
`actorType`, `actorId` — e um `payload` versionado com os dados específicos. Nunca se serializa
agregado, entidade JPA ou DTO interno para o broker: seria compartilhar implementação entre
contextos por outro caminho, que é o que ADR-0011 proíbe.

Tópico por agregado não é regra universal. O tópico é fronteira operacional e contratual, e agrupa
eventos com propriedades compatíveis de ownership, retenção, segurança, volume, ordenação, evolução
de schema e consumidores — algo como `fk.credito.solicitacoes-limite.v1`, `fk.risco.avaliacoes.v1`,
`fk.movimentacoes.lancamentos.v1`, cada um podendo transportar mais de um tipo de evento desde que
os contratos sejam explicitamente versionados. A partition key também não é o aggregate id por
convenção cega: é a unidade sobre a qual o negócio precisa de ordenação, que frequentemente coincide
com o agregado, mas pode ser a solicitação quando o que importa é a sequência de avaliações
relacionadas a ela. Kafka não oferece ordenação global, e só precisamos da ordenação que o negócio
exige.

## Consequências

A entrega é **at-least-once**: Outbox e brokers podem redeliverar, e consumidores são idempotentes
por construção. Não afirmamos exactly-once fim a fim atravessando PostgreSQL, Outbox, broker e outro
PostgreSQL.

O caminho assíncrono precisa de recuperação: comando perdido, consumer morto ou evento não entregue
não podem deixar uma solicitação parada em silêncio. Além do caminho primário existe reconciliação —
`Credito` consulta o estado autoritativo em `Risco` por uma interface REST idempotente, exatamente
como faz com o Core em ADR-0009. Esse REST é mecanismo de recuperação e não substitui o fluxo
assíncrono.

Manter dois brokers é custo aceito porque cada um carrega uma semântica distinta. Se um dia só uma
das duas semânticas existir, o outro sai.
