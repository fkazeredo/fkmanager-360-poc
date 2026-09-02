# Domain Docs

How the engineering skills should consume this repo's domain documentation when exploring the codebase.

## This repo is multi-context

Treat `fkmanager-360-poc` as a **multi-context** repo. `CONTEXT-MAP.md` at the root is the entry
point: it lists the bounded contexts (`IdentidadeEAcesso`, `CarteiraClientes`, `Credito`, `Risco`,
`Movimentacoes`), their responsibilities, their relations, and which of them are cross-cutting
capabilities rather than contexts.

The repo is **in transition**. No context has been materialised in code yet — there is no `src/` —
so the per-context `CONTEXT.md` files described below do not exist. Until they do, the root
`CONTEXT.md` is the consolidated glossary for every context, with terms grouped by context.
`CONTEXT-MAP.md` records this and says when each context's vocabulary moves out.

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
├── CONTEXT.md                         ← consolidated glossary, grouped by context (transitional)
└── docs/adr/                          ← system-wide decisions
    ├── 0001-linguagem-ubiqua-em-portugues.md
    └── ...
```

Target state, reached one context at a time as specs materialise them:

```
/
├── CONTEXT-MAP.md
├── docs/adr/                          ← system-wide decisions
└── src/
    ├── credito/
    │   ├── CONTEXT.md
    │   └── docs/adr/                  ← context-specific decisions
    └── carteira-clientes/
        ├── CONTEXT.md
        └── docs/adr/
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

## Flag ADR conflicts

If your output contradicts an existing ADR, surface it explicitly rather than silently overriding:

> _Contradicts ADR-0007 (maker-checker as a domain invariant), but worth reopening because…_

If your output contradicts `CONTEXT-MAP.md` — proposing a new bounded context, promoting a
cross-cutting capability to a context, or crossing a boundary the map doesn't record — say so
explicitly. The map is derived from ADR-0003; changing it is an ADR-level decision, not a
side effect of implementation.
