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

- [x] AC22 — selecionar um `Cliente` devolve suas `ContaCorrente`s, completando o critério iniciado em
      #0001
- [x] AC29 (parcial) — o limite apresentado é o `LimiteChequeEspecialVigente` que o `CoreLegado`
      reconhece no momento da consulta, lido pela ACL de `Credito`; nenhum valor local ou derivado é
      apresentado como limite do `Cliente`. A marcação de pendente e a virada após `EFETIVADA`
      pertencem a #0003 e #0005
- [x] AC23 — sem direito de atendimento atual, a consulta do `LimiteChequeEspecialVigente` por conta
      responde `403` e **nenhuma** chamada ao `CoreLegado` é emitida; a recusa é produzida pelo backend
      dono do recurso e continua valendo quando a requisição chega sem passar pelas restrições de
      navegação do `app-gerente`
- [x] AC30 — o modelo de apresentação é montado pelo `bff-gerente` a partir de
      `servico-carteira-clientes` e `servico-credito`; o BFF não fala com o Core;
      `servico-carteira-clientes` não conhece `LimiteChequeEspecial`; e `servico-credito` não devolve
      dados cadastrais do `Cliente` que pertencem a `CarteiraClientes`
- [x] AC21 — o Token Exchange encadeado fecha: `servico-credito`, ao continuar a operação em nome do
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
Token Exchange encadeado).

S3 **pequeno e apenas em `servico-carteira-clientes`**, cobrindo exclusivamente a nova consulta
`existeVinculo`: o ticket não introduz schema, migration nem database, mas essa query passa a ser o
guard que precede toda chamada ao `CoreLegado`, e um falso positivo nela autorizaria um atendimento
indevido. Reusa o harness Testcontainers do #0001, sem infraestrutura nova. **Nenhum S3 em
`Credito`**, que não tem persistência.

## Log

**2026-09-02** — Implementação concluída e verificada contra a stack real (`docker compose up`),
não apenas por teste isolado.

**A decisão estruturante do ticket.** O direito de atendimento persistido é `gerente ↔ cliente`; o
vínculo `conta → cliente` é do `CoreLegado`. Uma primeira versão do plano resolvia `contaId →
clienteId` pela ACL **antes** de autorizar, o que violava a própria regra do ticket. A ordem final é:
o atendimento carrega `clienteId` **e** `contaId`; `CarteiraClientes` verifica o vínculo na sua
persistência local (consulta barata, sem rede); sem vínculo, `403` e **zero** chamadas ao Core; só
então o Core é consultado, e sempre pela chave já autorizada — "quais contas são deste `Cliente`" —,
contra a qual a pertinência da conta é confirmada. O `clienteId` recebido nunca é tratado como verdade
sobre a quem a conta pertence: ele é apenas a chave que torna possível a verificação barata.

Efeito colateral que simplificou o contrato: como o Core só é consultado por `codCli` já autorizado,
o simulador **não** tem consulta de conta por `numCta`, e a ausência é deliberada — não existe caminho
em que uma conta seja chave de entrada sem autorização prévia.

**Construído.** `simulador-core-legado` ganhou duas capacidades host-centric (contas de um `Cliente`,
sem nada financeiro; dados de crédito de uma conta, sem nada cadastral). `servico-carteira-clientes`
ganhou `existeVinculo`, a ACL de contas, os dois casos de uso de atendimento e os endpoints
`/clientes/{id}/contas` e `/clientes/{id}/contas/{contaId}/contexto-atendimento`.
`servico-credito` nasceu — módulo Maven novo, porta 8083, **sem persistência**: sem `credito_db`, sem
Flyway, sem papel no `postgres-init`, e uma regra ArchUnit que falha se JDBC/DataSource/Flyway
aparecerem enquanto não houver estado durável. `bff-gerente` passou a compor a tela a partir dos dois
contextos. `app-gerente` ganhou a tela de atendimento.

**Cadeia de Token Exchange, sem amplificação de privilégio.** Login com `openid carteira.leitura
credito.leitura`; BFF troca por `aud=servico-credito` com `credito.leitura carteira.leitura`;
`servico-credito` troca de novo por `aud=servico-carteira-clientes` pedindo **apenas**
`carteira.leitura` — a segunda perna estreita capability. Três travas independentes:
`servico-credito` tem um único scope registrado no Authorization Server, sua allow-list de targets
tem um único destino, e o `TokenExchangePolicyAuthenticationProvider` (renomeado, antes só validava
target) passou a exigir que o scope pedido esteja contido no scope do **subject token** —
`invalid_scope`, sem emitir token. Cada Resource Server continua validando `aud` e scope localmente.

**`consultadoEm` não é `datAtuLim`.** O host informa quando *ele* atualizou o limite; isso não
responde "estes fatos são de agora?". `consultadoEm` vem de um `Clock` injetado, carimbado pela ACL
após a tradução bem-sucedida. `datAtuLim` é validado (`yyyyMMdd`; inválido → `502`) e permanece
encapsulado na ACL. Um teste com `Clock` fixo e `datAtuLim` de 2020 fixa a distinção.

**Verificação.** 180 testes Java (`./mvnw clean verify` sobre o reactor inteiro: 15
`fk-simulador-core-legado`, 79 `fk-servico-carteira-clientes`, 60 `fk-servico-credito`, 16
`fk-bff-gerente`, 10 `fk-servidor-autorizacao`), 10 testes Angular, 4 contratos OpenAPI validados,
rebuild das 6 imagens, cold start dos 8 serviços, e 9 testes Playwright contra a stack real —
incluindo os quatro passos novos da jornada 1: seleção de conta com o limite exato do Core
(R$ 5.000,00), composição do BFF sem o browser falar com nenhum outro backend, e `403`/`404`
chegando direto ao backend sem passar pela navegação do Angular.

Seams: S2 (orquestração com fakes que contam invocações), S3 pequeno — **só** `existeVinculo`, contra
PostgreSQL real, porque a consulta virou o guard que precede todo acesso ao Core —, S4 (as duas ACLs
contra WireMock), S5 (smoke contra o simulador real), S6 (segurança, Token Exchange encadeado,
composição), S7 (jornada 1 estendida, nenhuma jornada nova), S8 (ArchUnit no módulo novo). **Sem S3 em
`Credito`**, que não tem persistência.

`Credito` materializado: vocabulário movido de `CONTEXT.md` raiz para
`docs/contextos/credito/CONTEXT.md`; `CONTEXT-MAP.md` e `docs/agents/domain.md` atualizados.

**Overfetch reconhecido, não silenciado.** `contexto-atendimento` devolve dados cadastrais porque o
BFF os usa para compor a tela; `servico-credito` declara um record só com `clienteId` e nunca
desserializa o resto, mas os dados trafegam. Separar uma operação de autorização pura exigiria
evidência que este ticket não tem — registrado aqui para que a decisão seja revisitável.

**Não implementado (fora de escopo, fecha em #0003+):** `SolicitacaoAumentoLimite`,
`ContextoDecisaoCredito`, `PoliticaCredito`, `MotorDecisaoCredito`, `credito_db`, Outbox,
idempotência, efetivação, dispatcher, reconciliação, callback, e o control plane do simulador.

**Status: aguardando code review final.** A implementação está completa e verificada, e os
Acceptance Criteria acima estão marcados porque cada um tem evidência executável. O ticket permanece
`open` deliberadamente: o fechamento definitivo só acontece depois do `/code-review` sobre
`develop...feature/0002-selecao-de-conta-e-limite-vigente` e do tratamento dos achados, no mesmo
padrão do #0001 — onde a revisão encontrou cinco IMPORTANT que a suíte verde não pegava.
