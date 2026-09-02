# Domain Docs

How the engineering skills should consume this repo's domain documentation when exploring the codebase.

## This repo is multi-context

Treat `fkmanager-360-poc` as a **multi-context** repo. `CONTEXT-MAP.md` at the root is the entry
point: it lists the bounded contexts (`IdentidadeEAcesso`, `CarteiraClientes`, `Credito`, `Risco`,
`Movimentacoes`), their responsibilities, their relations, and which of them are cross-cutting
capabilities rather than contexts.

The repo is **in transition**. `IdentidadeEAcesso` and `CarteiraClientes` were materialised by
ticket #0001 and have their own `src/<context>/CONTEXT.md`; `Credito`, `Risco` and `Movimentacoes`
have not, so their vocabulary still lives in the root `CONTEXT.md`, grouped by context.
`CONTEXT-MAP.md` records which is which and says when each remaining context's vocabulary moves
out.

## Before exploring, read these

- **`CONTEXT-MAP.md`** at the repo root: read it first, then read the vocabulary for whichever
  contexts your topic touches.
- **`CONTEXT.md`** at the repo root: the consolidated glossary while contexts are unmaterialised.
  Once a context is materialised, its terms live in `src/<context>/CONTEXT.md` instead.
- **`docs/adr/`**: read ADRs that touch the area you're about to work in. Once contexts are
  materialised, also check `src/<context>/docs/adr/` for context-scoped decisions.

If any of these files don't exist, **proceed silently**. Don't flag their absence; don't suggest
creating them upfront. The `/domain-modeling` skill (reached via `/grill-with-docs` and
`/improve-codebase-architecture`) creates them lazily when terms or decisions actually get resolved.

Don't create `src/<context>/CONTEXT.md` files speculatively. A context's glossary file appears when
the spec that materialises that context appears, and not before (ADR-0010).

## File structure

Current state of this repo:

```
/
├── CONTEXT-MAP.md                     ← entry point: the contexts and their relations
├── CONTEXT.md                         ← consolidated glossary for unmaterialised contexts
├── docs/adr/                          ← system-wide decisions
└── src/
    ├── identidade-e-acesso/
    │   └── CONTEXT.md                 ← materialised by #0001
    └── carteira-clientes/
        └── CONTEXT.md                 ← materialised by #0001
```

Target state, reached one remaining context at a time as specs materialise them:

```
/
├── CONTEXT-MAP.md
├── docs/adr/                          ← system-wide decisions
└── src/
    ├── identidade-e-acesso/
    │   └── CONTEXT.md
    ├── carteira-clientes/
    │   └── CONTEXT.md
    └── credito/
        ├── CONTEXT.md
        └── docs/adr/                  ← context-specific decisions, when one exists
```

## Use the glossary's vocabulary

When your output names a domain concept (in a spec or ticket title, a refactor proposal, a hypothesis,
a test name), use the term as defined in the glossary. Don't drift to synonyms the glossary
explicitly avoids — the `_Evitar_` lines are normative.

A concept belongs to a context. The same word can mean different things in two contexts (see
`ContaCorrente` in `CarteiraClientes` versus `Movimentacoes`); name the context when the term is
ambiguous.

If the concept you need isn't in the glossary yet, that's a signal: either you're inventing
language the project doesn't use (reconsider) or there's a real gap (note it for
`/domain-modeling`).

## Naming convention: domain language vs. technical language

> Domain language uses PT-BR and ASCII identifiers according to the ubiquitous language. Technical
> software-engineering terminology uses English. Mixed names may combine a PT-BR domain concept
> with an English technical qualifier.

This governs how the glossary's vocabulary becomes code, not just prose. Getting it wrong in one
ticket (#0001 did, and was refactored) teaches the wrong pattern to every ticket that copies it.

**DOMAIN — stays PT-BR.** Any identifier naming a concept from `CONTEXT.md` or a materialised
`src/<context>/CONTEXT.md`: `Cliente`, `CarteiraClientes`, `GerenteRelacionamento`, `ContaCorrente`,
`SolicitacaoAumentoLimite`, `DecisaoCredito`, `LimiteChequeEspecial`. Methods whose name is directly
domain behaviour, read as ubiquitous language, may also stay PT-BR (`executar` on a use case
representing the action itself; `indisponivel()` as a `DadosMestresCliente` factory).

**TECHNICAL — English.** Packages that describe architectural structure, not domain, follow
hexagonal (ports & adapters) in English: `domain`, `application` (`application/port/{in,out}`,
`application/usecase`), `adapter` (`adapter/in/web`, `adapter/out/persistence`, `adapter/out/legacy`),
`config`. Class/interface qualifiers: `Controller`, `Repository`, `Adapter`, `Client`, `Config`,
`Filter`, `Validator`, `Converter`, `Port`, `Request`, `Response`, `Exception`, `Handler`. Generic
infrastructure concepts that aren't domain vocabulary even when they sound close — session
(`Sessao`→`Session`), pagination (`Paginacao`→`Pagination`, `PaginaResultado`→`PageResult`),
security config (`SegurancaConfig`→`SecurityConfig`) — follow the same rule: they're not defined as
ubiquitous-language terms in any `CONTEXT.md`, so they're technical, not domain.

**MIXED — domain noun + English technical qualifier.** `ClienteRepositoryAdapter`,
`ListarClientesDaCarteira` (a use case name can stay fully domain-flavoured, per the project's own
established convention), `CoreLegadoUnavailableException` (`CoreLegado` is a protected actor name;
`Unavailable`+`Exception` are technical). Don't translate word-by-word — decide per identifier which
part is the domain noun (keep) and which part is the technical shape (translate).

**Protected names — never rename for this.** Bounded context and deployable names already fixed by
`CONTEXT-MAP.md`/ADR-0013 (`carteira-clientes`, `identidade-e-acesso`, `servico-carteira-clientes`,
`servidor-autorizacao`, `simulador-core-legado`, `bff-gerente`, `app-gerente`) are not technical
naming and don't move. Neither do external contracts: JSON field/query-param names already
exercised by another service, Angular, or a test (`pagina`, `tamanho`, `totalElementos`,
`cpfMascarado`, `codCli`/`nomCli`/... — the simulator's own fictional host-centric fields, ADR-0005)
stay exactly as published. Rename the internal identifier around a contract boundary, never the
contract itself, "por estética".

Comments, exception/log message strings, and test method names stay PT-BR prose per ADR-0001 —
this rule is about identifiers (packages, classes, interfaces, methods, significant variables), not
about prose.

## Flag ADR conflicts

If your output contradicts an existing ADR, surface it explicitly rather than silently overriding:

> _Contradicts ADR-0007 (maker-checker as a domain invariant), but worth reopening because…_

If your output contradicts `CONTEXT-MAP.md` — proposing a new bounded context, promoting a
cross-cutting capability to a context, or crossing a boundary the map doesn't record — say so
explicitly. The map is derived from ADR-0003; changing it is an ADR-level decision, not a
side effect of implementation.
