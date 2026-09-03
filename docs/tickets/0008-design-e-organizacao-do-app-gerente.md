---
id: 0008
title: Design system, UX de teclado e organizacao por feature no fk-app-gerente
state: closed
closed: 2026-09-03
triage: ready-for-agent
spec: docs/specs/slice-1-straight-through-approval.md
blocked_by: []
assignee: claude
created: 2026-09-03
---

# 0008 — Design system, UX de teclado e organizacao por feature no fk-app-gerente

## Objetivo

Refactoring de apresentacao e estrutura do `fk-app-gerente`, sem mudanca de comportamento de
negocio: o gerente passa a trabalhar numa interface com cara de banco moderno (paleta propria em
tons de vermelho, cartoes, hierarquia tipografica clara), navegavel primariamente por teclado, com
um guia de atalhos acessivel pelo icone `?`. O codigo Angular passa a seguir a organizacao por
feature que o mercado usa (`core/`, `features/`, `shared/`).

Nao ha endpoint novo, contrato novo nem regra de negocio nova — os ACs de origem (AC22, AC29,
AC37) continuam cobertos pelos mesmos testes, ajustados apenas onde o markup mudou.

## Acceptance Criteria

- [x] Design system global com tokens (cores, tipografia, espacamento, raios, sombras) em
      `styles.css`; paleta primaria em tons de vermelho propria, sem uso de marca de terceiros.
- [x] Layout master-detail: carteira a esquerda, atendimento a direita, com estado vazio orientando
      a selecao.
- [x] Navegacao por teclado: setas navegam a lista de clientes e as contas, Enter seleciona,
      `?` abre o guia de atalhos, teclas de foco rapido para carteira/contas/valor.
- [x] Guia de atalhos como dialog acessivel, aberto pelo icone `?` no cabecalho ou pela tecla `?`.
- [x] Codigo organizado por feature: `core/` (sessao, modelos do contrato BFF, teclado),
      `features/carteira/`, `features/atendimento/`, `shared/ui/`.
- [x] Testes unitarios e build de producao verdes.

## Blocked by

Nenhum — pode comecar imediatamente.

## Out of Scope

- Roteamento/lazy loading (o app continua uma unica tela master-detail).
- Qualquer mudanca de contrato com o bff-gerente ou de comportamento das jornadas.
- Tickets #0004–#0007.

## Testing

Os testes existentes (unitarios e e2e) continuam exercitando as mesmas jornadas; seletores sao
preservados onde possivel e ajustados onde o markup novo exigir.

## Log

### 2026-09-03 — claude

Fechado. Unitarios 34/34 e build de producao verdes sem ajuste nos testes; e2e 11/11 contra a
stack Compose real (todos os seletores preservados). Observacao operacional descoberta no
caminho: a suite e2e nao e re-executavel sem reset do estado do `credito_db` — a jornada de
aprovacao deixa uma SolicitacaoAumentoLimite AGUARDANDO_EFETIVACAO (nao-terminal ate #0005), e a
re-execucao recebe 409 SOLICITACAO_NAO_TERMINAL_EXISTENTE. Limitacao pre-existente, nao deste
ticket.

### 2026-09-03 — claude (iteracao 2, feedback do usuario)

Tres problemas reportados em teste manual, todos fora do Angular:

1. **Pagina de login sem estilo** — era a pagina default do Spring Security no
   servidor-autorizacao. Novo `LoginPageController` (adapter/in/web) serve `/login` no design
   system da plataforma; `formLogin.loginPage("/login").permitAll()` no chain default. Contrato
   do formulario preservado (`#username`, `#password`, `button[type=submit]`, POST /login, _csrf).
2. **"Sair" nao deslogava de verdade** — o BFF so encerrava a sessao local; a sessao SSO no
   servidor-autorizacao continuava viva e "Entrar" logava de volta sem senha. Agora o logout e
   RP-Initiated (OIDC): o BFF devolve a URL publica de `/connect/logout` com `id_token_hint` +
   `post_logout_redirect_uri` num corpo JSON, a SPA navega ate la, e o nginx ganhou o proxy de
   `/connect/`. Testes de BffSegurancaTest atualizados (200+redirectUrl; caso OIDC novo).
3. **Aviso "Nao seguro"** — certificado autoassinado de dev (SAN ok: localhost/127.0.0.1);
   resolve-se confiando `certs/dev-localhost.crt` no store do usuario, decisao do usuario.

UX v2 (pesquisa: padrao Nubank/fintech 2026): saudacao pessoal, filtro de clientes com atalho
`/`, contas como cards, raio 20px, alvos de toque >= 44px (WCAG), responsividade mobile real
(painel empilha, nome do usuario some do header em telas estreitas).
