# Issue tracker: markdown files in this repo

Specs and tickets live as **markdown files under `docs/`**, versioned alongside the code and reviewed
as diffs. This repo does **not** use the GitHub issue tracker. Do not create GitHub issues, and do not
reach for `gh issue` — if you find yourself wanting an issue, write a file instead.

## Layout

```
docs/
├── specs/                       one file per specification
│   └── slice-1-straight-through-approval.md
└── tickets/                     one file per ticket
    └── 0001-<slug>.md
```

Normativo, e vale sobre qualquer default de skill:

- specs vivem em `docs/specs/<slug>.md`;
- tickets vivem em `docs/tickets/NNNN-<slug>.md`;
- ambos são versionados em git e revisados como diff;
- **não** usar `.scratch/<feature-slug>/issues/` como destino final — é local descartável, e este
  layout o substitui;
- **não** usar `gh issue create`, nem qualquer outra escrita no tracker do GitHub;
- `/to-tickets` escreve os arquivos diretamente em `docs/tickets/`, em ordem de dependência
  (bloqueadores primeiro), usando o template abaixo.

## Identifiers

A ticket is `docs/tickets/NNNN-<slug>.md`, where `NNNN` is zero-padded and allocated as the highest
existing id plus one. Reference a ticket as `#NNNN`. A spec is referenced by its path, or by its slug
when the context is unambiguous.

Ids are never reused, and a file is never deleted to "free" one. Closing is a state change, not a
removal — the history lives in git.

## Front matter

Every spec and every ticket opens with YAML front matter. Tickets:

```yaml
---
id: 0007
title: Materializar servico-credito e a PoliticaCredito v1
state: open            # open | closed
triage: ready-for-agent
spec: docs/specs/slice-1-straight-through-approval.md
blocked_by: [0005]     # ticket ids; omit or [] when none
assignee:              # empty until claimed
created: 2026-09-02
---
```

Specs carry `title`, `state`, `triage` and `created`; the rest is ticket-only. When a ticket closes,
add `closed: YYYY-MM-DD` and set `state: closed`.

`triage` takes one of the five canonical values in `docs/agents/triage-labels.md`.

## Ticket template

O corpo mínimo de um ticket. Um ticket é uma fatia vertical executável, **não** um mini design
document: ele não repete o raciocínio da spec, aponta para ela.

```markdown
# NNNN — <título>

## Objetivo

O comportamento vertical que este ticket faz funcionar, do ponto de vista de quem usa — não uma lista
de camadas a construir.

## Acceptance Criteria

- [ ] critério verificável derivado da spec, referenciando o AC de origem quando houver
- [ ] ...

## Blocked by

Os tickets que genuinamente gateiam este, ou "nenhum — pode começar imediatamente".

## Out of Scope

Quando necessário para impedir que este ticket antecipe trabalho de outro.

## Testing

Quais seams da spec este tracer bullet exercita.
```

Não inclua antecipadamente paths, nomes de classe ou DDL que o ticket deve descobrir durante a
implementação — exceto quando a spec já os tornou contrato.

## Conventions

- **Create a spec**: write `docs/specs/<slug>.md` with front matter and the spec body.
- **Create a ticket**: allocate the next id, write `docs/tickets/NNNN-<slug>.md`, and set `spec:` to
  the spec it implements.
- **Read a spec or ticket**: read the file, including its `## Log`.
- **List**: search the front matter. Open tickets ready for an agent, for example, are the files under
  `docs/tickets/` matching both `state: open` and `triage: ready-for-agent`; ripgrep with
  `--multiline` or two passes intersected. There is no query language here — that is the accepted cost.
- **Comment**: append to the `## Log` section at the end of the file, newest last, each entry headed
  `### YYYY-MM-DD — <author>`. Never rewrite an earlier entry; the log is append-only, and git holds
  the rest of the history.
- **Apply / remove a triage label**: edit the `triage:` field.
- **Close**: set `state: closed`, add `closed:`, and append a `## Log` entry saying why. Keep the file.

## Pull requests as a triage surface

**PRs as a request surface: no.** _(Set to `yes` if this repo treats external PRs as feature requests;
`/triage` reads this flag.)_

The PR remains the **review** surface: because specs and tickets are files, a change to either travels
in a normal PR and is reviewed as a diff, next to the code it governs.

## When a skill says "publish to the issue tracker"

Write the markdown file under `docs/specs/` or `docs/tickets/` as above. Publishing means the file
exists on a branch; the PR is where it gets reviewed.

## When a skill says "fetch the relevant ticket"

Read `docs/tickets/NNNN-<slug>.md`.

## Wayfinding operations

Used by `/wayfinder`. The **map** is a single file with **child** tickets.

- **Map**: `docs/tickets/MAP.md`, holding the Notes / Decisions-so-far / Fog sections plus a task list
  of its children in map order.
- **Child ticket**: a normal ticket file carrying `map: MAP` in its front matter and listed in the
  map's task list. Its `triage` still comes from the canonical five; the wayfinder type goes in a
  `type:` field (`research` / `prototype` / `grilling` / `task`).
- **Blocking**: the `blocked_by:` front-matter list. A ticket is unblocked when every id it names has
  `state: closed`.
- **Frontier query**: the map's children with `state: open`, dropping any with an unclosed id in
  `blocked_by` or a non-empty `assignee`; first in map order wins.
- **Claim**: set `assignee:` — the session's first write.
- **Resolve**: append the answer to the ticket's `## Log`, set `state: closed` with `closed:`, then
  append a pointer to the map's Decisions-so-far naming the ticket file.

## Why files instead of GitHub issues

The spec and the code it governs evolve in the same pull request, review happens on the diff, and the
whole history is in git rather than in a service. An agent working offline reads the tracker with the
same tools it reads the codebase.

The cost is accepted deliberately: no issue UI, no cross-repo linking, no notifications, and listing
or filtering is grep over front matter rather than a query. If those become the bottleneck, moving back
is a matter of rewriting this file and importing the markdown.
