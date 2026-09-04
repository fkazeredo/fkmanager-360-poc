---
id: 0005
title: O CoreLegado confirma e a solicitação termina EFETIVADA
state: closed
triage: ready-for-agent
spec: docs/specs/slice-1-straight-through-approval.md
blocked_by: [0004]
assignee: claude
created: 2026-09-02
closed: 2026-09-04
---

# 0005 — O CoreLegado confirma e a solicitação termina EFETIVADA

## Objetivo

O caminho feliz fecha. O `CoreLegado` confirma o resultado chamando de volta o `servico-credito`, e o
callback entra pelo mesmo `RegistrarResultadoEfetivacao` criado em #0004 — não existe segunda
implementação da regra de conclusão. Só depois da confirmação autoritativa o `LimiteSolicitado` passa
a figurar como `LimiteChequeEspecialVigente`.

Junto vêm as patologias que a entrega at-least-once traz: duplicado idêntico, antecipado,
contraditório sobre estado terminal, sucesso incoerente e `efetivacaoId` desconhecido. O endpoint é
máquina-a-máquina, autenticado por `client_credentials`.

A jornada 1 do Playwright fecha aqui, com autenticação real e sem bypass de segurança.

## Acceptance Criteria

- [x] AC1 — a jornada completa: conta regular, valores na faixa automática, `APROVADA` com
      `versaoPoliticaCredito = v1`, `AGUARDANDO_EFETIVACAO`, uma única intenção durável, e após a
      confirmação autoritativa `EFETIVADA`, com a consulta posterior ao Core apresentando o novo
      vigente e o histórico contendo cada fato uma única vez
- [x] AC13 — callback duplicado idêntico devolve `200`, o estado fica inalterado, o número de entradas
      funcionais de histórico não muda e nenhum efeito é repetido
- [x] AC14 — callback que chega antes do registro do aceite localiza a operação por `EfetivacaoId`,
      conclui, aprende o `ProtocoloCore`, e o processamento posterior da resposta de aceite não regride
      o estado; protocolo igual é no-op idempotente
- [x] AC17 — callback contraditório sobre estado terminal devolve `2xx`, não reescreve o estado, não
      inventa transição no histórico, não inicia nova efetivação e registra anomalia observável
- [x] AC26 — callback de sucesso com `limiteEfetivado` incompatível não transiciona para `EFETIVADA`
      nem sobrescreve o resultado esperado; registra anomalia; a operação permanece recuperável, e um
      resultado autoritativo coerente posterior ainda a conclui
- [x] AC29 — o valor solicitado só passa a figurar como vigente depois de `EFETIVADA` e de leitura
      autoritativa do Core
- [x] AC36 (parcial) — os meters de resultado de efetivação e de anomalia de callback respeitam a
      política de cardinalidade da spec

## Blocked by

- #0004 — o callback correlaciona por `EfetivacaoId` e conclui pelo caso de uso criado lá.

## Out of Scope

O reconciliador e `EFETIVACAO_INDETERMINADA`, que pertencem a #0006. Tratamento humano de callback
contraditório, que está fora do slice. `efetivacaoId` desconhecido responde `404` e registra a
ocorrência; nenhuma capacidade de investigação além disso.

## Testing

S2 (convergência no caso de uso único), S3 (histórico não duplica sob redelivery), S6 (autenticação
máquina-a-máquina, status codes do callback, `404`), S7 (**jornada 1**).

## Log

### 2026-09-04 — claude

Trabalhado inteiramente em Plan Mode antes de qualquer edição, com quatro rodadas de correção do
Owner sobre o plano (convergência do callback antecipado com a TX-B do dispatcher; protocolo
divergente é sempre contraditório mesmo com resultado/valor coerentes; semântica precisa de
"uma tentativa" — nunca at-least-once — do `CallbackDispatcher` do simulador; conclusão
concorrente deve carregar o terminal observado, não assumir `FALHA_EFETIVACAO` cegamente; unidade
transacional única para `executarSobClaim`, nunca duas transações sucessivas) até aprovação
explícita. Implementação, testes e gates rodaram depois, autônomos.

**O que foi construído — `fk-servico-credito`.** `ResultadoEfetivacaoRecebido.Sucesso` (variante
que #0004 já previa); `ResultadoRegistroEfetivacao` virou sealed (`Concluida`,
`JaTerminalIdentica`, `JaTerminalContraditoria`, `SucessoIncoerente`, `ProtocoloDivergente`) com a
classificação terminal em três eixos, sempre nesta ordem: protocolo, resultado/motivo, limite
efetivado quando sucesso — protocolo divergente é contradição mesmo com os outros dois eixos
coerentes, nunca duplicado. `JpaResultadoEfetivacaoAdapter.registrar` ganhou
`Optional<ProtocoloCore> protocoloInformado`: aprende o protocolo (e grava `ACEITE:`) só na
primeira vez que qualquer caminho o traz, idêntico ao que `registrarAceite` já fazia — é isso que
resolve AC14 sem código novo de "convergência" dedicado, e comprovado com o adversarial exato do
Owner em `JpaResultadoEfetivacaoAdapterTest.callbackAntecipado_concluiPrimeiro_...`.
`ResultadoConclusaoDefinitiva.ConcluidaPorOutroCaminho(terminalObservado, contraditoria)` substitui
o antigo `IllegalStateException` de #0004: quando `executarSobClaim` descobre que outro caminho já
terminalizou, o terminal PERSISTIDO dita como a entrega termina
(`EntregasEfetivacaoPort.terminalizarPorConclusaoConcorrente` — `EFETIVADA`→`ACEITA`,
`FALHA_EFETIVACAO`→`FALHA_DEFINITIVA`), nunca o resultado perdedor do dispatcher; `executarSobClaim`
continua uma ÚNICA unidade transacional (`TransacaoPort` de #0004, reutilizada) com a ordem global
de locks preservada (`outbox_entrega` → `solicitacao_aumento_limite`; o callback puro nunca toca
`outbox_entrega` — é isso que garante ausência de deadlock, provado com concorrência real em
`executarSobClaim_concorrenteComCallbackDireto_semDeadlockETerminalPersistidoCoerente`).
`CallbackEfetivacaoController` (`POST /callbacks/efetivacoes`, sem Bean Validation — este módulo
não depende de `spring-boot-starter-validation`, validação manual como `SolicitacaoAumentoLimiteRequest`
já fazia) traduz a sealed para `200` (`PROCESSADO`/`JA_CONCLUIDA`/`CONFLITO_REGISTRADO`/
`ANOMALIA_REGISTRADA`), `404` (`efetivacaoId` desconhecido) e `400` (payload malformado).
`SecurityConfig` ganhou o matcher `credito.callback` sem `hasRole` — token `client_credentials`
nunca carrega `papeis`.

**`fk-servidor-autorizacao`.** Terceiro client (`simulador-core-legado`, `CLIENT_CREDENTIALS`,
scope único `credito.callback`) com o novo `ClientSettings` `fk.client-credentials.audience`; o
`jwtCustomizer` ganhou um ramo próprio para `CLIENT_CREDENTIALS` que lê essa setting e preenche
`aud` — até aqui só o ramo de Token Exchange preenchia audience, e um token de máquina sem `aud`
seria recusado pelo `AudienceValidator` do Resource Server.

**`fk-simulador-core-legado`.** `EfetivacoesLegadoStore.registrarAceite` devolve
`ResultadoRegistroAceite(registro, criadoAgora)`: `criadoAgora` é `true` para NO MÁXIMO uma
chamada concorrente por `idEft` (`ConcurrentHashMap#computeIfAbsent` já garante isso — só faltava
expor o sinal), e só essa execução agenda o processamento (prova de concorrência real com 16
threads em `EfetivacoesLegadoStoreTest`). `ContasLegadoStore` trocou para
`Collections.synchronizedMap(LinkedHashMap)` — não `ConcurrentHashMap`, porque `findByCodCli`
depende da ordem de inserção do seed (prova já existente em `ContaLegadoControllerTest`), que
`ConcurrentHashMap` não garante; `findByCodCli` sincroniza explicitamente na iteração (exigência do
próprio contrato de `synchronizedMap`, achado do `/code-review`). `ProcessadorEfetivacaoLegado`
agenda (com `TaskScheduler`, atraso configurável ~0.5s) a mutação do limite + UMA tentativa de
callback — nunca reagendada por conta própria, mesmo no cenário `PerderAceite`, porque a resposta
perdida não significa que o processamento não aconteceu. `TokenClienteCredentials`: cliente
`client_credentials` minimo e isolado (sem `spring-security-oauth2-client`), cache de um único
token com dupla checagem sob trava — só o refresh entra na seção crítica, e uma segunda checagem
dentro dela evita N requisições simultâneas ao token endpoint quando N threads encontram o token
expirado ao mesmo tempo (prova com 8 threads concorrentes em `TokenClienteCredentialsTest`); nunca
loga token nem client secret. `CallbackDispatcher`: fire-and-forget deliberado — a Javadoc é
explícita que isto NÃO é entrega at-least-once (quem é idempotente é o endpoint de destino);
falha na obtenção do token aborta a tentativa sem nunca enviar sem Bearer.

**Testes.** S1 nenhum novo (máquina de estados já exaustiva desde #0003); S2
`RegistrarResultadoEfetivacaoTest` 13 (inclui os dois adversariais de conclusão concorrente nas
duas direções exigidos pelo Owner) e `EntregarInstrucoesEfetivacaoTest` 6 (ajustado à sealed); S3
novo `JpaResultadoEfetivacaoAdapterTest` 8 (Testcontainers, IP-da-bridge — callback direto,
duplicado, contraditório, sucesso incoerente, convergência AC14 completa, conclusão concorrente
nas duas direções contra o banco, e o teste de concorrência real sem deadlock);
`JdbcEntregasEfetivacaoAdapterTest` de #0004 revalidado (14, intacto); S6 novo
`CallbackSegurancaTest` 16 (autenticação, autorização por scope sem role, os quatro sub-casos de
`200`, `404`, `400`, AC36 por inspeção do registry) e `CreditoSegurancaTest` 19 revalidado; no
simulador: `ProcessadorEfetivacaoLegadoTest` 3, `CallbackDispatcherTest` 4, `TokenClienteCredentialsTest`
6, `EfetivacoesLegadoStoreTest` 3, `EfetivacaoLegadoControllerTest` 8 (atualizado);
`ArchitectureTest` 15 (intacto — nenhuma regra de hex-arch quebrada pelo callback). Reator completo
(JDK 25, `./mvnw clean verify` com `-fae`): as únicas falhas são as quatro já documentadas no log
de #0004 como achado de ambiente desta sessão (timeouts apertados colidindo com latência real do
Docker-outside-of-Docker em `ClienteLegadoAclAdapterTest`/`CreditoLegadoAclAdapterTest`, a
wait-strategy de porta publicada do Testcontainers Redis em `BffSegurancaTest`, e a ausência do
binário `docker` CLI dentro do container de build para os dois `SimuladorCoreLegadoSmokeTest` que
dependem de `docker build` via `ProcessBuilder`) — nenhuma em código deste ticket, e nenhuma delas
em módulo tocado por ele (exceto o próprio smoke de `fk-servico-credito`, cuja nova asserção foi
verificada manualmente contra a stack real, ver abaixo).

**`/code-review` sobre o diff completo — dois achados corrigidos antes do commit.**
(1) `ContasLegadoStore.findByCodCli` iterava `values().stream()` sobre um `synchronizedMap` sem
sincronizar explicitamente na iteração — contrato exige a trava manual mesmo quando cada operação
individual do wrapper já é sincronizada; corrigido com `synchronized (records)`. (2) `.env.example`
não tinha `AUTH_SERVER_SIMULADOR_CLIENT_ID`/`AUTH_SERVER_SIMULADOR_CLIENT_SECRET` — um checkout
novo seguindo o template não subiria `servidor-autorizacao` (fail-fast, sem default para o
secret); corrigido.

**Verificação end-to-end.** `docker compose down -v` + `up --build`: 8/8 serviços saudáveis, sem
WARN/ERROR inesperado nos logs de `servico-credito` e `simulador-core-legado`. Submissão aprovada
(conta 10002) confirmada em `credito_db` via `psql`: `status=EFETIVADA`, `protocolo_core` presente
(`000000000001`, contador do simulador), histórico com os cinco fatos
(`SOLICITACAO_REGISTRADA`/`DECISAO_AUTOMATICA_REGISTRADA`/`EFETIVACAO_SOLICITADA`/
`INSTRUCAO_ACEITA_PELO_CORE`/`RESULTADO_EFETIVACAO_REGISTRADO`) exatamente uma vez cada, autor
`CORE_LEGADO` no resultado. Playwright (`cd e2e && npm test`): **11/11** — a jornada 1 agora fecha
de ponta a ponta (extensão do teste de #0003, sem criar jornada nova): submete, observa
`AGUARDANDO_EFETIVACAO` com o vigente antigo e o solicitado pendente (AC29), reseleciona a conta
até o vigente novo aparecer, e confirma via `psql` (helper novo `e2e/tests/db-credito.ts`) o estado
final em `credito_db`. 4 contratos OpenAPI válidos (`node scripts/validar-openapi.mjs`):
`fk-servico-credito` regenerado ao vivo (`info.version` `0003`→`0005`, path `/callbacks/efetivacoes`
novo); `fk-simulador-core-legado` regenerado ao vivo (mesma versão `0004` — nenhuma mudança de
contrato inbound, só a nota de descrição atualizada).

**Fora de escopo, confirmado intacto:** #0006 (reconciliador, `EFETIVACAO_INDETERMINADA`, consulta
de status por protocolo/`EfetivacaoId`) não foi implementado. Tratamento humano de callback
contraditório permanece fora do slice.

**Ticket fechado.**
