---
id: 0006
title: Callback perdido é recuperado, e resultado desconhecido vira EFETIVACAO_INDETERMINADA
state: open
triage: ready-for-agent
spec: docs/specs/slice-1-straight-through-approval.md
blocked_by: [0005]
assignee:
created: 2026-09-02
---

# 0006 — Callback perdido é recuperado, e resultado desconhecido vira EFETIVACAO_INDETERMINADA

## Objetivo

Callback não é infalível. Um reconciliador passa a varrer as efetivações paradas além do prazo e a
**perguntar** o resultado ao `CoreLegado` — por `ProtocoloCore` quando conhecido, por `EfetivacaoId`
quando não —, convergindo no mesmo `RegistrarResultadoEfetivacao`. Ele nunca reenvia a instrução: quem
entrega é o dispatcher de #0004. A operação de consulta de status no `simulador-core-legado`, por
ambos os identificadores, nasce aqui, porque este é o primeiro comportamento que a consome.

Quando nem callback nem reconciliação produzem resposta autoritativa dentro da janela normal, a
solicitação entra em `EFETIVACAO_INDETERMINADA`. O estado afirma ignorância, não falha: o limite pode
ter sido efetivado, continua bloqueando nova solicitação para a conta, e uma resposta posterior ainda
o conclui. Esgotar a recuperação nunca produz `FALHA_EFETIVACAO`.

Os prazos são parâmetros operacionais, não SLA e não regra de domínio, reduzíveis no profile `test`.
Nenhum teste espera minutos reais.

As jornadas 3 e 4 do Playwright fecham aqui, e com elas o conjunto de quatro jornadas da spec.

## Acceptance Criteria

- [ ] AC12 — suprimido o callback, a reconciliação consulta por protocolo ou `EfetivacaoId`, converge
      por `RegistrarResultadoEfetivacao`, a solicitação termina `EFETIVADA`, o histórico atribui a
      conclusão ao mecanismo de reconciliação e nenhuma segunda efetivação é enviada
- [ ] AC16 — esgotada a janela sem resultado autoritativo, o estado passa a
      `EFETIVACAO_INDETERMINADA` e **não** a `FALHA_EFETIVACAO`; nenhuma nova solicitação para a conta
      é aceita; um callback autoritativo posterior conclui em `EFETIVADA`; e existe cobertura
      equivalente para a conclusão tardia em falha autoritativa
- [ ] AC34 — com duas instâncias do reconciliador executando simultaneamente sobre o mesmo conjunto de
      pendentes, cada efetivação é reclamada por uma única claim lógica por ciclo, nenhuma é
      processada duas vezes no mesmo ciclo e nenhuma fica órfã, contra PostgreSQL real e sem eleição
      de líder
- [ ] AC35 — ao entrar em `EFETIVACAO_INDETERMINADA` a métrica é incrementada, o log estruturado é
      emitido com os identificadores necessários e o mecanismo de alerta recebe o sinal; nenhum desses
      artefatos afirma que houve falha de efetivação
- [ ] AC37 — `EFETIVACAO_INDETERMINADA` renderiza como acompanhamento, com o aviso de que nova
      solicitação para aquela conta não pode ser iniciada, e nunca como erro; com isso o critério de
      mensagens fecha
- [ ] AC36 — os meters introduzidos aqui respeitam a política de cardinalidade, e com este ticket o
      critério fecha para todo o slice: nenhum meter de #0003 a #0006 carrega `clienteId`, `contaId`,
      `solicitacaoId`, `protocoloCore` ou `correlationId`

## Blocked by

- #0005 — as jornadas de callback perdido e de conclusão tardia pressupõem que o mecanismo de callback
  exista para poder ser suprimido, atrasado ou recebido depois.

## Out of Scope

Reenvio de instrução pelo reconciliador, que violaria a fronteira. Eleição de líder. Tratamento humano
de operação indeterminada. Reconciliação de lotes, que pertence a outro slice.

## Testing

S2 (reconciliador consulta e converge, sem lógica de transição duplicada no scheduler), S3 (claim
transacional com instâncias concorrentes), S5 (consulta de status por ambos os identificadores), S6
(métrica, log e alerta), S7 (**jornadas 3 e 4**).
