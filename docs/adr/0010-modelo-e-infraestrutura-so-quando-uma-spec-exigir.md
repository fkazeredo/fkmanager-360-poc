# Modelo e infraestrutura só aparecem quando uma spec os exige

O princípio que governa a evolução deste projeto: **cada slice acrescenta comportamento observável
de ponta a ponta, e infraestrutura nova só aparece quando o comportamento daquele slice cria a
necessidade.**

Isso vale também para o modelo de domínio. O `StatusSolicitacaoAumentoLimite` contém apenas os
estados que o slice atual exercita — `SOLICITADA`, `AGUARDANDO_EFETIVACAO`, `EFETIVADA`,
`REJEITADA`, `FALHA_EFETIVACAO`. Estados como `EM_AVALIACAO_RISCO`, `AGUARDANDO_PARECER` ou
`AGUARDANDO_APROVACAO` não existem, e passam a existir quando uma spec introduzir comportamento
que os necessite. Numa POC cujo objeto é Spec-Driven Development, estados que nenhuma spec pediu
seriam a contradição mais visível possível.

## O primeiro slice

*Straight-Through Approval*: autenticação do gerente, listagem simples da carteira, seleção de
cliente, visualização de uma conta corrente, consulta do limite vigente no Core, criação da
`SolicitacaoAumentoLimite`, captura imutável do `ContextoDecisaoCredito`, execução de uma política
automática extremamente simples, decisão automática — aprovando **ou rejeitando** —, início
confiável da efetivação, confirmação pelo Core, estado final `EFETIVADA`, e histórico da própria
solicitação servido pelo `servico-credito`.

A rejeição automática entra desde o slice 1 porque um motor que só sabe aprovar não representa
política de decisão: é carimbo. Rejeição é terminal e não gera tentativa de efetivação.

Ficam fora: AvaliacaoRisco e `servico-risco`, AnalistaCredito e ParecerCredito, decisão do
SupervisorCredito, múltiplos níveis de alçada, extrato completo, importação noturna, Spring Batch,
staging, reconciliação de lotes, Kafka, `servico-auditoria`, `servico-notificacoes`, UI
administrativa e busca avançada.

## Sequência planejada

Slice 2 — casos inconclusivos: `Risco`, processamento assíncrono, `AvaliacaoRisco`, RabbitMQ onde
o padrão command/work queue for adequado. Slice 3 — decisão humana: AnalistaCredito,
ParecerCredito, SupervisorCredito, `PerfilAlcadaAprovacao`, maker-checker completo. Slice 4 —
`Movimentacoes`, extrato e indicadores. Slice 5 — importação legada: arquivo noturno, Spring
Batch, staging, deduplicação, reconciliação, restart e reprocessamento. Slice 6 — eventos
transversais em Kafka e consumidores independentes.

A sequência não é definitiva e as specs podem refiná-la. O princípio é.

## Consequências

Detalhes de contrato pertencem à spec do slice que os introduz, não a rodadas globais de discovery
antecipado: nomes definitivos de endpoints, schemas JSON, constraints SQL, campos do Outbox e
estratégia de idempotency key são decididos no slice. Esse limite existe para impedir que discovery
vire Big Design Up Front.

## Emenda — 2026-09-02: `EFETIVACAO_INDETERMINADA` entra no slice 1

O grilling do slice 1 exercitou um cenário que a lista acima não previa: o Core aceita a instrução, a
resposta de aceite se perde, o callback não chega e as consultas de reconciliação falham. Não há
evidência de que a efetivação tenha falhado — o limite pode ter sido alterado —, e concluir
`FALHA_EFETIVACAO` gravaria um fato possivelmente falso.

Por isso `EFETIVACAO_INDETERMINADA` passa a integrar o `StatusSolicitacaoAumentoLimite` do slice 1,
como estado não terminal (ADR-0009, emenda de 2026-09-02). Isso não contraria a regra desta
decisão — a reforça: o estado entra porque um comportamento deste slice o exige, e não porque alguém
antecipou o workflow futuro. `EM_AVALIACAO_RISCO`, `AGUARDANDO_PARECER` e `AGUARDANDO_APROVACAO`
continuam não existindo.
