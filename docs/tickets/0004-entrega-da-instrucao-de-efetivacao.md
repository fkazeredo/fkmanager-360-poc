---
id: 0004
title: A aprovação vira instrução entregue ao CoreLegado
state: open
triage: ready-for-agent
spec: docs/specs/slice-1-straight-through-approval.md
blocked_by: [0003]
assignee:
created: 2026-09-02
---

# 0004 — A aprovação vira instrução entregue ao CoreLegado

## Objetivo

A intenção durável que #0003 gravou passa a ser entregue: um dispatcher a lê e envia a instrução ao
`CoreLegado` com `EfetivacaoId` e `limiteChequeEspecialVigenteEsperado`, e o Core responde com aceite
e `ProtocoloCore`, que fica persistido. Aceite não é conclusão — a solicitação permanece em
`AGUARDANDO_EFETIVACAO`.

Nascem aqui a taxonomia de quatro classes da ACL — aceite, transitório, definitivo e indeterminado — e
o caso de uso `RegistrarResultadoEfetivacao`, porque uma recusa definitiva já no aceite precisa
concluir a solicitação. Esse caso de uso é único: #0005 e #0006 acrescentam entradas para ele, nunca
uma segunda implementação da regra de conclusão.

No `simulador-core-legado` entram a operação de efetivação com deduplicação funcional por
`EfetivacaoId` e os cenários de control plane de aceite perdido e indisponibilidade. A deduplicação é
comportamento funcional do Core, não capacidade do control plane. A consulta de status por protocolo
e por `EfetivacaoId` **não** nasce aqui: ela nasce em #0006, que é quem a consome — aqui o protocolo
perdido é recuperado pelo próprio reenvio da instrução.

A fronteira é estrita desde já: o dispatcher **entrega**. Consultar resultado é #0006.

## Acceptance Criteria

- [ ] AC11 — perdida a resposta de aceite, o reenvio usa o mesmo `EfetivacaoId`, o Core não aplica a
      alteração de novo, o mesmo `ProtocoloCore` é recuperado e existe uma única operação lógica de
      efetivação
- [ ] AC28 — diante de falha transitória, a instrução é reenviada com o mesmo `EfetivacaoId` até o
      limite configurado, com backoff; esgotado o limite o dispatcher para, a solicitação permanece em
      `AGUARDANDO_EFETIVACAO` e **não** transiciona para `FALHA_EFETIVACAO`; nunca se cria uma segunda
      operação lógica
- [ ] AC15 — precondição de vigente violada produz `LIMITE_VIGENTE_DIVERGENTE` e `FALHA_EFETIVACAO`,
      sem segunda operação lógica, sem nova `DecisaoCredito` e sem recálculo automático; nova tentativa
      exige nova `SolicitacaoAumentoLimite`
- [ ] AC1 (parcial) — a aprovação de #0003 produz exatamente uma instrução entregue, e o
      `ProtocoloCore` fica persistido com a solicitação ainda em `AGUARDANDO_EFETIVACAO`
- [ ] AC36 (parcial) — os meters introduzidos aqui, incluindo tempo de permanência em
      `AGUARDANDO_EFETIVACAO`, respeitam a política de cardinalidade da spec

## Blocked by

- #0003 — sem decisão aprovada e sem intenção durável registrada não há o que entregar.

## Out of Scope

O callback, que pertence a #0005. Qualquer consulta de status por iniciativa nossa e o reconciliador,
que pertencem a #0006. `EFETIVACAO_INDETERMINADA` não existe ainda: esgotar retries apenas encerra a
tentativa de entrega e deixa a solicitação onde está.

## Testing

S2 (dispatcher separado do reconciliador por construção), S3 (intenção durável, `messageId` estável e
metadado de tentativa separado dele), S4 (as quatro classes e as patologias que as produzem), S5
(dedup por `EfetivacaoId`, mesmo protocolo recuperado no reenvio, precondição do vigente esperado).
