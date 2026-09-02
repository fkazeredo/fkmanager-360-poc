---
id: 0002
title: Gerente seleciona a conta e vê o LimiteChequeEspecialVigente
state: open
triage: ready-for-agent
spec: docs/specs/slice-1-straight-through-approval.md
blocked_by: [0001]
assignee:
created: 2026-09-02
---

# 0002 — Gerente seleciona a conta e vê o LimiteChequeEspecialVigente

## Objetivo

A tela de atendimento passa a existir: o gerente escolhe um `Cliente`, escolhe uma `ContaCorrente` e
vê o `LimiteChequeEspecialVigente` que o `CoreLegado` reconhece agora. A tela é composta pelo
`bff-gerente` a partir de dois contextos — identidade e vínculo de `CarteiraClientes`, limite de
`Credito` pela ACL própria daquele contexto.

É o primeiro ticket em que `servico-credito` existe, e ele nasce **sem database**: ainda não há estado
durável de `Credito`. Criar persistência aqui seria infraestrutura antes da necessidade.

A verificação do direito de atendimento acontece antes de qualquer acesso ao Core. Sem direito atual,
a resposta é `403` e nenhuma chamada ao `CoreLegado` sai.

## Acceptance Criteria

- [ ] AC22 — selecionar um `Cliente` devolve suas `ContaCorrente`s, completando o critério iniciado em
      #0001
- [ ] AC29 (parcial) — o limite apresentado é o `LimiteChequeEspecialVigente` que o `CoreLegado`
      reconhece no momento da consulta, lido pela ACL de `Credito`; nenhum valor local ou derivado é
      apresentado como limite do `Cliente`. A marcação de pendente e a virada após `EFETIVADA`
      pertencem a #0003 e #0005
- [ ] AC23 — sem direito de atendimento atual, a consulta do `LimiteChequeEspecialVigente` por conta
      responde `403` e **nenhuma** chamada ao `CoreLegado` é emitida; a recusa é produzida pelo backend
      dono do recurso e continua valendo quando a requisição chega sem passar pelas restrições de
      navegação do `app-gerente`
- [ ] AC30 — o modelo de apresentação é montado pelo `bff-gerente` a partir de
      `servico-carteira-clientes` e `servico-credito`; o BFF não fala com o Core;
      `servico-carteira-clientes` não conhece `LimiteChequeEspecial`; e `servico-credito` não devolve
      dados cadastrais do `Cliente` que pertencem a `CarteiraClientes`
- [ ] AC21 — o Token Exchange encadeado fecha: `servico-credito`, ao continuar a operação em nome do
      usuário contra `servico-carteira-clientes`, apresenta token obtido por Token Exchange com a `aud`
      correta

## Blocked by

- #0001 — a sessão, o Token Exchange e a listagem da carteira precisam existir antes de haver conta a
  selecionar.

## Out of Scope

Registro de solicitação, `ContextoDecisaoCredito`, `PoliticaCredito` e qualquer persistência de
`Credito` — tudo em #0003. `credito_db` **não** nasce aqui.

## Testing

S2 (orquestração da leitura), S4 (tradução do contrato host-centric para limite, situação da conta e
classificação de risco base), S5 (deriva entre o adapter de `Credito` e o simulador), S6 (`403` e
Token Exchange encadeado). Sem S3: este ticket não introduz persistência.
