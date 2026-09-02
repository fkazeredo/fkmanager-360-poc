---
id: 0007
title: Gerente acompanha suas solicitações e o histórico
state: open
triage: ready-for-agent
spec: docs/specs/slice-1-straight-through-approval.md
blocked_by: [0003]
assignee:
created: 2026-09-02
---

# 0007 — Gerente acompanha suas solicitações e o histórico

## Objetivo

O gerente volta à aplicação depois e reencontra o que originou. `MinhasSolicitacoesAumentoLimite`
lista, paginada e da mais recente para a mais antiga, somente as solicitações cujo `originadorId` é o
ator autenticado, e cada item abre detalhes e o histórico funcional daquela solicitação, com o
`AtorOperacao` de cada fato visível — automação tem autor, não origem vazia.

Aqui também fica explícita a separação entre duas autorizações que o slice trata como distintas:
acesso ao **processo de Crédito**, que segue o `originadorId`, e acesso ao **recurso atual** em
`CarteiraClientes`, que segue o direito de atendimento de hoje. A primeira não concede a segunda.

Este ticket não depende da efetivação: ele lista e detalha o que existir. Pode ser desenvolvido em
paralelo a #0004, #0005 e #0006, e passa a exibir os fatos de efetivação conforme esses tickets os
produzem.

## Acceptance Criteria

- [ ] AC24 — a listagem devolve somente solicitações cujo `originadorId` é o ator autenticado, nunca
      as de outro gerente; é paginada; vem ordenada da mais recente para a mais antiga; e cada item
      permite abrir detalhes e o histórico daquela solicitação
- [ ] AC25 — dado que um gerente originou uma solicitação e que o `Cliente` depois deixou sua
      carteira, ele continua conseguindo consultar aquela solicitação e seu histórico, e **não**
      consegue, por causa dessa permissão histórica, abrir os dados atuais do `Cliente`, consultar a
      `ContaCorrente` atual, consultar o limite atual nem originar nova solicitação. As duas
      autorizações são exercitadas separadamente

## Blocked by

- #0003 — a listagem precisa de solicitações e de trilha de histórico persistidas.

## Out of Scope

Busca avançada, filtros complexos, listagem por `Cliente`, por `ContaCorrente` ou por carteira, e
visões de supervisor ou auditor. Enriquecer a listagem com dados atuais de `CarteiraClientes`, que
contradiria a autorização histórica.

## Testing

S2 (filtro por originador e as duas autorizações na aplicação), S3 (paginação e ordenação contra
PostgreSQL real), S6 (autorização de recurso na fronteira HTTP).
