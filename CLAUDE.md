# fkmanager-360-poc

## Documentary precedence

`CONTEXT.md` and `CONTEXT-MAP.md` define the ubiquitous language and the domain boundaries. ADRs define durable architectural decisions. A spec defines the behaviour and scope of one feature within those constraints. Tickets decompose a spec for execution.

A spec or a ticket must not contradict an ADR or the ubiquitous language. Changing either means changing the appropriate upper source first, explicitly — a ticket never decides architecture or renames a domain concept for convenience.

## Git workflow

GitFlow. `main` is the release line; `develop` is the integration line and the base for all new work. Neither takes a direct commit — everything enters by Pull Request into `develop` (except `hotfix/*`, which targets `main`). Branch types: `feature/NNNN-<slug>`, `spec/<slug>`, `docs/<slug>`, `bugfix/<slug>`, `release/*`, `hotfix/*`. See `docs/adr/0022-gitflow-como-estrategia-de-branching.md`.

## Agent skills

### Issue tracker

Specs and tickets live as markdown files under `docs/specs/` and `docs/tickets/`, versioned with the code and reviewed as diffs. This repo does not use GitHub issues. See `docs/agents/issue-tracker.md`.

### Triage labels

The five canonical triage roles, each label string equal to its role name. See `docs/agents/triage-labels.md`.

### Domain docs

Multi-context: `CONTEXT-MAP.md` at the root is the entry point. No context is materialised in code yet, so the root `CONTEXT.md` is still the consolidated glossary; ADRs live in `docs/adr/`. See `docs/agents/domain.md`.
