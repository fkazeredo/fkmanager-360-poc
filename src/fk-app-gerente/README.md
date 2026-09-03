# AppGerente

SPA Angular do GerenteRelacionamento. Fala exclusivamente com o `fk-bff-gerente` (sessao por
cookie, CSRF, nunca um token no browser -- ADR-0015); na stack integrada e servida pelo nginx em
`https://localhost:4200`, que tambem faz proxy de `/bff/`, `/oauth2/` e `/login`.

## Servidor de desenvolvimento

```bash
ng serve
```

Abre em `http://localhost:4200/`, com reload automatico.

> **Nota sobre a porta 4200**: este e o mesmo numero de porta que `compose.yaml` publica para o
> `app-gerente` (nginx, com TLS, em `https://localhost:4200`) desde a decisao do Owner registrada em
> `docs/adr/0013-deployable-e-delimitado-por-perfil-de-execucao.md`. A coincidencia e inofensiva
> porque os dois nunca rodam ao mesmo tempo: ou se sobe a stack integrada via Compose (nginx serve a
> SPA buildada e faz proxy de `/bff/`, `/oauth2/` e `/login`), ou se roda `ng serve` localmente
> contra um `bff-gerente` publicado a parte (usando `proxy.conf.json`, hoje praticamente inerte
> porque o Compose nao publica a porta do `bff-gerente`). Rodar os dois processos ao mesmo tempo
> nesta mesma maquina colidiria na porta 4200 -- nao e um cenario suportado.

## Build e testes

```bash
ng build   # artefatos em dist/
ng test    # unitarios via Vitest
```

Os testes end-to-end da stack inteira nao vivem aqui: sao Playwright, em `e2e/` na raiz do
repositorio, contra o Compose completo.
