---
id: 0002
title: Gerente seleciona a conta e vê o LimiteChequeEspecialVigente
state: closed
triage: ready-for-agent
spec: docs/specs/slice-1-straight-through-approval.md
blocked_by: [0001]
assignee:
created: 2026-09-02
closed: 2026-09-02
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

S2 (orquestração da leitura, incluindo o primitivo `ConfirmarDireitoDeAtendimento` separado da
composição rica), S4 (tradução do contrato host-centric para limite, situação da conta e
classificação de risco base), S5 (deriva entre o adapter de `Credito` e o simulador), S6 (`403`,
`404`, a taxonomia completa de erro na borda do BFF, e o Token Exchange encadeado com política de
target único e scope explícito).

S3 **pequeno e apenas em `servico-carteira-clientes`**, cobrindo exclusivamente a nova consulta
`existeVinculo`: o ticket não introduz schema, migration nem database, mas essa query passa a ser o
guard que precede toda chamada ao `CoreLegado`, e um falso positivo nela autorizaria um atendimento
indevido. Reusa o harness Testcontainers do #0001, sem infraestrutura nova. **Nenhum S3 em
`Credito`**, que não tem persistência.

## Log

### 2026-09-02 — franklin.azeredo

Implementação concluída e verificada contra a stack real (`docker compose up`),
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

**Overfetch — inicialmente ACCEPTED TRADEOFF, RESOLVED no code review desta mesma data (ver entrada
seguinte no Log).** A primeira versão desta implementação aceitava que `contexto-atendimento` devolvia
dados cadastrais que `servico-credito` recebia na rede sem usar. O review trouxe evidência concreta de
um custo real além do overfetch em si — acoplamento de disponibilidade: uma falha na consulta de dados
mestres do Cliente dentro de `CarteiraClientes` derrubava a leitura do limite, mesmo essa consulta
sendo dispensável para Credito. A correção separou as duas operações: `/direito-de-atendimento` (nova,
`204` sem corpo) é o primitivo de autorização que `servico-credito` consome; `/contexto-atendimento`
continua rica, e passa a ser usada apenas pelo `bff-gerente` para compor a tela (AC30).

**Não implementado (fora de escopo, fecha em #0003+):** `SolicitacaoAumentoLimite`,
`ContextoDecisaoCredito`, `PoliticaCredito`, `MotorDecisaoCredito`, `credito_db`, Outbox,
idempotência, efetivação, dispatcher, reconciliação, callback, e o control plane do simulador.

**Status nesta data: aguardando code review.** Ver a entrada seguinte no Log para o resultado da
revisão e das correções.

### 2026-09-02 — franklin.azeredo

`/code-review` sobre `develop...feature/0002-selecao-de-conta-e-limite-vigente` (delta completo, não
só o commit inicial): 1 BLOCKER, 10 IMPORTANT, 5 COSMETIC, 1 ACCEPTED TRADEOFF. Todos corrigidos nesta
mesma data (commit `fix: address ticket 0002 code review findings`).

**BLOCKER — corrida de requisições no Angular.** `carregarContas`/`selecionarConta` faziam
`.subscribe()` direto por seleção, sem cancelamento: uma resposta lenta para uma seleção anterior
podia chegar depois de uma nova seleção e sobrescrever a tela — o limite autoritativo de uma conta
aparecendo sob outra. Corrigido com `switchMap` sobre `toObservable(cliente)` e sobre um
`Subject<ContaResumo>` de seleção, mais `takeUntilDestroyed()` para cleanup. Provado com o cenário
adversarial exato (conta A pendente → conta B selecionada → resposta B chega → resposta A chega depois
→ tela mostra só B), usando `TestRequest.cancelled` do Angular como evidência de que a assinatura
antiga foi genuinamente cancelada, não apenas ignorada por sorte de timing.

**A mudança estrutural do round: separar autorização estreita de composição rica.** O overfetch que a
primeira versão aceitou como tradeoff escondia um custo real — acoplamento de disponibilidade: Credito
dependia indiretamente da consulta de dados mestres do Cliente, que nunca usa. Nasceu
`ConfirmarDireitoDeAtendimento` em `CarteiraClientes`, o primitivo de autorização (vínculo local +
pertinência da conta, sem nada cadastral), exposto em `GET .../direito-de-atendimento` → `204`.
`ConsultarContextoAtendimento` passou a compor esse primitivo com a consulta de dados mestres, sem
duplicar a chamada ao Core. `servico-credito` migrou para o endpoint estreito; o endpoint rico
`/contexto-atendimento` continua existindo, só para o `bff-gerente`. Efeito colateral: a classe de
defeito "`clienteId` malformado devolvido pelo peer" (que exigia tratamento na desserialização do
corpo rico) deixou de existir estruturalmente, porque o endpoint que `Credito` consome não tem corpo.

**Canonicalização de `ContaId`.** `CarteiraClientes` comparava contas por igualdade de string
sem normalizar; `Credito` normalizava via `HostFormat.toCodigoHost`. O mesmo número de conta,
padded ou não, podia ser 404 num contexto e resolver no outro. Corrigido no próprio value object —
o construtor compacto canonicaliza (remove zero-padding) antes de armazenar — nos dois contextos.

**Taxonomia de erro completa no `bff-gerente`.** Antes, só 403/404/5xx tinham handler; um 400 ou 401
de qualquer backend escapava para 500 genérico. Agora: entrada inválida na própria borda do BFF → 400
(`clienteId`/`contaId` validados antes de qualquer chamada remota); 401 de um Resource Server (token
delegado recusado) → 502, nunca 401 — a sessão do browser continua válida, e reencaminhar como 401
confundiria "usuário precisa logar de novo" com "a cadeia de Token Exchange quebrou"; 403/404 →
atravessam sem reinterpretação; qualquer 4xx inesperado, corpo `2xx` incompleto, ou 5xx → 502/503.

**Limite nunca vira zero por ausência.** `long limiteChequeEspecialVigente` virou `Long`: um corpo sem
o campo desserializava silenciosamente para `0`, que é exatamente o valor que o domínio documenta como
"Cliente sem cheque especial" — a única leitura que precisava ser distinguível de ausência era a que o
primitivo colapsava com ela. Ausência agora é `DependenciaRespostaInvalidaException` → 502.

**Parsing `STRICT` de `datAtuLim`.** O resolver `SMART` (default) corrigia silenciosamente datas
impossíveis — `29/02/2025` (ano não bissexto) virava `28/02/2025` em vez de lançar, exatamente a
assinatura de um registro host corrompido. `ResolverStyle.STRICT` fecha isso; testado com data válida,
29/02 em ano bissexto (aceito) e 29/02 em ano comum (rejeitado).

**Evidência real para AC30, no lugar da vácua.** O teste anterior afirmava "o BFF não fala com o Core"
subindo um `WireMockServer` cujo endereço nenhum componente conhecia — verde por construção.
Substituído por duas provas falsificáveis: nenhuma classe de `fk-simulador-core-legado` no classpath
do `bff-gerente` (o módulo não é dependência Maven) e o único conjunto de beans `RestClient` do
contexto são exatamente os dois destinos autorizados.

**Token Exchange: exatamente um destino, scope sempre explícito.** `TokenExchangePolicyAuthenticationProvider`
aceitava zero destinos (caindo no default do Spring, que podia coincidir com uma audience válida) e
tratava scope ausente como "sem verificação" — dependência implícita de um comportamento do provider
padrão que a própria classe não controla. Agora exige exatamente um destino e scope explícito sempre
contido no subject token, com `invalid_target`/`invalid_scope` e nenhum token emitido nos negativos
(target ausente, dois destinos simultâneos mesmo ambos permitidos isoladamente, scope ausente).

**Orçamento de timeout documentado.** Os timeouts eram valores isolados por serviço, alguns menores que
o pior caso declarado do próprio destino que chamavam — o BFF podia desistir de `servico-credito`
exatamente quando este estava prestes a responder. Redesenhado bottom-up (CoreLegado e Token Exchange
como folhas de 5s; cada consumidor = pior caso declarado do destino + 3s de margem), documentado como
tabela no Javadoc de `TokenExchangeConfig`, e as chamadas de Token Exchange — que não tinham timeout
algum antes — ganharam um explícito.

**Verificação completa.** `./mvnw clean verify` sobre o reactor inteiro: 222 testes Java (13
`fk-servidor-autorizacao`, 95 `fk-servico-carteira-clientes`, 76 `fk-servico-credito`, 23
`fk-bff-gerente`, 15 `fk-simulador-core-legado`); 14 testes Angular; 4 contratos OpenAPI validados;
auditoria de segredos sem achados; busca por nomes residuais (`LimiteVigenteResponse`, provider antigo,
`contexto-atendimento` em Credito) sem ocorrências; `docker compose down -v` + rebuild das 6 imagens +
cold start dos 8 serviços, todos saudáveis; logs de todos os serviços sem erro ou warning; 9 testes
Playwright contra a stack real recém-construída, incluindo a jornada estendida de #0002 completa.

**Reavaliação do ACCEPTED TRADEOFF original (overfetch).** RESOLVED, não permanece como tradeoff — ver
a mudança estrutural acima.

**Re-review**: 0 BLOCKER, 0 IMPORTANT, todos os 17 achados (1 BLOCKER + 10 IMPORTANT + 5 COSMETIC + 1
ACCEPTED TRADEOFF reavaliado) RESOLVED, gates verdes, ACs dentro da fronteira do ticket satisfeitos.
**Ticket fechado.**
