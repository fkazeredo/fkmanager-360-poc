# GitFlow como estratégia de branching, `develop` como linha de integração

`main` é a linha de release: sempre deployável, e não recebe implementação cotidiana. `develop` é a
linha de integração: base de todo trabalho novo. Nenhuma das duas recebe commit direto — tudo entra
por Pull Request, revisado como diff, do mesmo jeito que `CLAUDE.md` já exige para spec, ticket e
código juntos.

Esta decisão adota o núcleo tradicional de GitFlow — `main`, `develop`, `feature/*`, `release/*`,
`hotfix/*` — e o estende com três tipos de branch próprios deste repositório, porque o tracker aqui é
markdown versionado (`docs/agents/issue-tracker.md`): spec e ticket viajam em branch como código
viaja.

## Fluxos

| Tipo | Nasce de | Volta para | Uso |
| --- | --- | --- | --- |
| `feature/NNNN-<slug>` | `develop` | `develop` | implementa um ticket |
| `spec/<slug>` | `develop` | `develop` | escreve ou revisa uma specification |
| `docs/<slug>` | `develop` | `develop` | documentação e processo |
| `bugfix/<slug>` | `develop` | `develop` | corrige comportamento ainda em `develop`, sem caráter emergencial de produção |
| `release/*` | `develop` | `main` **e** `develop` | estabiliza uma release; só aceita ajuste da própria release |
| `hotfix/*` | `main` | `main` **e** `develop` | corrige produção com urgência |

`release/*` e `hotfix/*` sempre retornam a `develop`: é o que impede a linha de release divergir da
linha de integração. `bugfix/*` é distinto de `hotfix/*` só pela origem — um nasce de `develop` porque
o defeito ainda não chegou a produção, o outro nasce de `main` porque já chegou.

Tagging e publicação de release não são automatizados por esta decisão — ainda não foram definidos, e
decidi-los aqui seria antecipar o que ninguém pediu.

## Por que branching vira ADR neste projeto

`CLAUDE.md` descreve ADR como decisão arquitetural durável. Estratégia de branching qualifica: ela
molda como todo trabalho subsequente entra no repositório e afeta diretamente o workflow dos agentes,
no mesmo nível que ADR-0011 já decide topologia de repositório.

## Alternativas rejeitadas

**Trunk-based com feature flags** — o custo do flag não se paga numa POC cujo objeto é demonstrar
Spec-Driven Development em fatias verticais; um slice atravessa cinco deployables e precisa de um
ponto de integração antes de virar release.

**GitHub Flow** (branch curta direto para `main`) — não distingue "integrado" de "publicado", e o
slice 1 tem sete tickets que só fazem sentido em conjunto.

O custo aceito também fica registrado: GitFlow é pesado para entrega contínua, e esta POC aceita isso
porque a unidade de valor aqui é o slice, não o commit.

## Retroatividade

Antes desta decisão o repositório não possuía `develop`, e o único Pull Request existente apontou para
`main` por não haver alternativa. Isso não é violação retroativa — o fluxo descrito aqui passa a ser
normativo a partir desta ADR, não antes dela.

## Consequências

`main` e `develop` ganham proteção de branch — Pull Request obrigatório, sem push direto, sem
force-push, sem deleção — aplicada inclusive a administradores. Nenhum required status check é exigido
ainda, porque não existe CI: exigir um check inexistente travaria todo Pull Request.

Todo trabalho novo — ticket, spec ou documentação — abre branch a partir de `develop` e retorna a
`develop` por Pull Request, nunca diretamente contra `main`.
