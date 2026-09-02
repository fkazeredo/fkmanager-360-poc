---
id: 0001
title: Gerente autentica e vê sua CarteiraClientes
state: open
triage: ready-for-agent
spec: docs/specs/slice-1-straight-through-approval.md
blocked_by: []
assignee:
created: 2026-09-02
---

# 0001 — Gerente autentica e vê sua CarteiraClientes

## Objetivo

O `GerenteRelacionamento` faz login de verdade e passa a ver a lista paginada dos `Cliente`s sob sua
responsabilidade. É o walking skeleton do slice: o menor comportamento que só funciona se a espinha
inteira estiver de pé — Angular, sessão no BFF, token audience-restricted, Resource Server e a ACL
própria de `CarteiraClientes` para os dados mestres do `Cliente`.

A infraestrutura nasce aqui porque este comportamento a exige, e não como objetivo próprio:
`app-gerente`, `bff-gerente`, `servidor-autorizacao`, `servico-carteira-clientes` com armazenamento e
migrations próprias, Redis para a sessão, `simulador-core-legado` com a operação de dados mestres do
`Cliente`, executor one-shot de migrations, Docker Compose, baseline de telemetria, ArchUnit,
Testcontainers e o harness Playwright.

Nada aqui pode ser simplificado: sem PKCE, sem sessão server-side, sem restrição de audience ou sem
autorização de recurso no backend dono, o ticket não terminou.

## Acceptance Criteria

- [ ] AC19 — a autenticação acontece contra o `servidor-autorizacao` por Authorization Code + PKCE +
      OIDC; ao final o browser possui apenas o cookie de sessão do `bff-gerente`, com os atributos de
      segurança esperados, e nem access token nem refresh token aparecem em resposta, corpo, storage
      ou qualquer superfície acessível ao Angular
- [ ] AC20 — a sessão sobrevive ao restart da instância do `bff-gerente` que a originou; o logout a
      invalida e uma requisição posterior com o mesmo cookie é recusada; uma requisição de escrita sem
      o token CSRF esperado é recusada, e a mesma requisição com o token é aceita
- [ ] AC21 (parcial) — o `bff-gerente` chama o Resource Server com token cuja `aud` é aquele destino;
      um token válido emitido para outro Resource Server é recusado pela validação de `aud`; os scopes
      exigidos permanecem capacidades grossas, sem nenhum scope que codifique política de crédito. O
      Token Exchange encadeado de `Credito` para `CarteiraClientes` fecha em #0002
- [ ] AC22 (parcial) — o gerente autenticado vê somente os `Cliente`s da sua `CarteiraClientes`, e a
      listagem é paginada. A seleção de `Cliente` e suas `ContaCorrente`s fecha em #0002
- [ ] AC30 (parcial) — o `bff-gerente` não emite nenhuma chamada ao `simulador-core-legado`, e
      `servico-carteira-clientes` não expõe nem conhece `LimiteChequeEspecial`. A composição a partir
      dos dois contextos fecha em #0002

## Blocked by

Nenhum — pode começar imediatamente.

## Out of Scope

`servico-credito` e qualquer leitura de limite, que pertencem a #0002. Persistência de `Credito`.
Nenhuma jornada Playwright nova: o harness é estabelecido aqui, e estas asserções passam a ser os
primeiros passos da jornada 1, que só fecha em #0005. As quatro jornadas canônicas continuam sendo as
da spec.

## Testing

S3 (persistência da associação e migrations), S4 (patologias do contrato host-centric na ACL de
`CarteiraClientes`), S5 (smoke contra o `simulador-core-legado` real), S6 (PKCE, cookie, CSRF, `aud`,
Token Exchange, autorização de recurso), S8 (regra de dependência desde o primeiro código). S7 apenas
como harness, sem criar jornada.
