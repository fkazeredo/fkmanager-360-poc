---
id: 0001
title: Gerente autentica e vê sua CarteiraClientes
state: closed
triage: ready-for-agent
spec: docs/specs/slice-1-straight-through-approval.md
blocked_by: []
assignee:
created: 2026-09-02
closed: 2026-09-02
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

- [x] AC19 — a autenticação acontece contra o `servidor-autorizacao` por Authorization Code + PKCE +
      OIDC; ao final o browser possui apenas o cookie de sessão do `bff-gerente`, com os atributos de
      segurança esperados, e nem access token nem refresh token aparecem em resposta, corpo, storage
      ou qualquer superfície acessível ao Angular
- [x] AC20 — a sessão sobrevive ao restart da instância do `bff-gerente` que a originou; o logout a
      invalida e uma requisição posterior com o mesmo cookie é recusada; uma requisição de escrita sem
      o token CSRF esperado é recusada, e a mesma requisição com o token é aceita
- [x] AC21 (parcial) — o `bff-gerente` chama o Resource Server com token cuja `aud` é aquele destino;
      um token válido emitido para outro Resource Server é recusado pela validação de `aud`; os scopes
      exigidos permanecem capacidades grossas, sem nenhum scope que codifique política de crédito. O
      Token Exchange encadeado de `Credito` para `CarteiraClientes` fecha em #0002
- [x] AC22 (parcial) — o gerente autenticado vê somente os `Cliente`s da sua `CarteiraClientes`, e a
      listagem é paginada. A seleção de `Cliente` e suas `ContaCorrente`s fecha em #0002
- [x] AC30 (parcial) — o `bff-gerente` não emite nenhuma chamada ao `simulador-core-legado`, e
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

## Log

**2026-09-02** — Ticket fechado. Walking skeleton vertical completo e verificado de ponta a ponta
contra a stack real (`docker compose up`), não apenas por teste unitário/integração isolado.

Construído: `simulador-core-legado` (contrato fictício host-centric), `servico-carteira-clientes`
(domínio + ACL + persistência + segurança, 40 testes: S3/S4/S5/S6/S8), `servidor-autorizacao`
(Authorization Code + PKCE + OIDC + Token Exchange via infraestrutura padrão do Spring Security,
sem cache de token proprietário), `bff-gerente` (sessão em Redis via Spring Session, CSRF,
`OAuth2AuthorizedClientManager`, 8 testes), `app-gerente` (Angular 22 standalone/zoneless), Docker
Compose com os 8 serviços, e o harness Playwright (S7, 4 testes contra a stack real).

68 testes automatizados verdes (40 `servico-carteira-clientes`, 8 `servidor-autorizacao`+migrations,
8 `bff-gerente`, 6 `simulador-core-legado`, 5 Angular, 4 Playwright), `./mvnw verify` completo e
`npm test` do Angular passando.

A verificação manual e o harness Playwright contra a topologia real (não mocks, não MockMvc)
encontraram e corrigiram bugs que a suíte automatizada anterior não pegava:

- Dockerfile de `servico-carteira-clientes` nunca fora criado; o build via `docker compose build`
  falhava de um jeito que parecia bug do builder bake, mas era só o arquivo ausente.
- `postgres:18` mudou a convenção de mount do volume de dados; `redis`'s healthcheck autenticava
  com senha vazia por variável de ambiente ausente no container; o shade plugin de
  `carteira-clientes-migracoes` descartava o SPI do `flyway-database-postgresql`; nginx só escutava
  IPv4. Todos quebravam a stack em runtime apesar de tudo compilar e os testes unitários passarem.
- `SessaoController` resolvia `OidcUser` sem `@AuthenticationPrincipal` — 500 em toda chamada
  autenticada a `/api/sessao`, nunca exercitado pela suíte porque nenhum teste chamava esse endpoint
  autenticado.
- O cookie `XSRF-TOKEN` nunca era emitido (`CsrfFilter` resolve o token via `Supplier` adiado, e uma
  API pura nunca lê `_csrf` para forçar essa resolução) — corrigido com um filtro que força a
  resolução em toda requisição, como a própria documentação do Spring Security recomenda para SPAs.
- Mesmo depois disso, o cookie tinha `Path=/bff` (herdado do context-path do servlet): uma página
  servida em `/` (app-gerente) nunca enxerga via `document.cookie` um cookie escopado a `/bff`, e o
  interceptor XSRF do Angular nunca encontrava nada para anexar como header. `curl` não reproduz
  esse sintoma porque não aplica escopo de `Path` na leitura — só apareceu no harness Playwright,
  com um browser de verdade.
- `oauth2Login` não tinha `defaultSuccessUrl`: o alvo padrão pós-login é `/`, e como `bff-gerente`
  tem context-path `/bff`, o container resolve esse redirect relativo contra o próprio context path
  (Servlet spec), pousando o browser em `/bff/` — 404, sem chegar nunca no `app-gerente`.

Todos corrigidos com teste de regressão (unitário onde fazia sentido, Playwright onde só um browser
real expõe o sintoma).

`IdentidadeEAcesso` e `CarteiraClientes` materializados: vocabulário movido de `CONTEXT.md` raiz
para `src/identidade-e-acesso/CONTEXT.md` e `src/carteira-clientes/CONTEXT.md`; `CONTEXT-MAP.md`
atualizado.

Não implementado (explicitamente fora de escopo, fecha em #0002+): `servico-credito`, leitura de
limite, Token Exchange encadeado `Credito` → `CarteiraClientes`, seleção de `Cliente` e suas
`ContaCorrente`s, composição de tela cliente+conta+limite, jornada Playwright nova (os quatro testes
deste ticket são os primeiros passos da jornada 1, que só fecha em #0005).
