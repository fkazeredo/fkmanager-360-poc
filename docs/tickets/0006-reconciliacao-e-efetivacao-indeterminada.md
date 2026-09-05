---
id: 0006
title: Callback perdido é recuperado, e resultado desconhecido vira EFETIVACAO_INDETERMINADA
state: closed
triage: ready-for-agent
spec: docs/specs/slice-1-straight-through-approval.md
blocked_by: [0005]
assignee: claude
created: 2026-09-02
closed: 2026-09-05
---

# 0006 — Callback perdido é recuperado, e resultado desconhecido vira EFETIVACAO_INDETERMINADA

## Objetivo

Callback não é infalível. Um reconciliador passa a varrer as efetivações paradas além do prazo e a
**perguntar** o resultado ao `CoreLegado` — por `ProtocoloCore` quando conhecido, por `EfetivacaoId`
quando não —, convergindo no mesmo `RegistrarResultadoEfetivacao`. Ele nunca reenvia a instrução: quem
entrega é o dispatcher de #0004. A operação de consulta de status no `simulador-core-legado`, por
ambos os identificadores, nasce aqui, porque este é o primeiro comportamento que a consome.

Quando nem callback nem reconciliação produzem resposta autoritativa dentro da janela normal, a
solicitação entra em `EFETIVACAO_INDETERMINADA`. O estado afirma ignorância, não falha: o limite pode
ter sido efetivado, continua bloqueando nova solicitação para a conta, e uma resposta posterior ainda
o conclui. Esgotar a recuperação nunca produz `FALHA_EFETIVACAO`.

Os prazos são parâmetros operacionais, não SLA e não regra de domínio, reduzíveis no profile `test`.
Nenhum teste espera minutos reais.

As jornadas 3 e 4 do Playwright fecham aqui, e com elas o conjunto de quatro jornadas da spec.

## Acceptance Criteria

- [x] AC12 — suprimido o callback, a reconciliação consulta por protocolo ou `EfetivacaoId`, converge
      por `RegistrarResultadoEfetivacao`, a solicitação termina `EFETIVADA`, o histórico atribui a
      conclusão ao mecanismo de reconciliação e nenhuma segunda efetivação é enviada
- [x] AC16 — esgotada a janela sem resultado autoritativo, o estado passa a
      `EFETIVACAO_INDETERMINADA` e **não** a `FALHA_EFETIVACAO`; nenhuma nova solicitação para a conta
      é aceita; um callback autoritativo posterior conclui em `EFETIVADA`; e existe cobertura
      equivalente para a conclusão tardia em falha autoritativa
- [x] AC34 — com duas instâncias do reconciliador executando simultaneamente sobre o mesmo conjunto de
      pendentes, cada efetivação é reclamada por uma única claim lógica por ciclo, nenhuma é
      processada duas vezes no mesmo ciclo e nenhuma fica órfã, contra PostgreSQL real e sem eleição
      de líder
- [x] AC35 — ao entrar em `EFETIVACAO_INDETERMINADA` a métrica é incrementada, o log estruturado é
      emitido com os identificadores necessários e o mecanismo de alerta recebe o sinal; nenhum desses
      artefatos afirma que houve falha de efetivação
- [x] AC37 — `EFETIVACAO_INDETERMINADA` renderiza como acompanhamento, com o aviso de que nova
      solicitação para aquela conta não pode ser iniciada, e nunca como erro; com isso o critério de
      mensagens fecha
- [x] AC36 — os meters introduzidos aqui respeitam a política de cardinalidade, e com este ticket o
      critério fecha para todo o slice: nenhum meter de #0003 a #0006 carrega `clienteId`, `contaId`,
      `solicitacaoId`, `protocoloCore` ou `correlationId`

## Blocked by

- #0005 — as jornadas de callback perdido e de conclusão tardia pressupõem que o mecanismo de callback
  exista para poder ser suprimido, atrasado ou recebido depois.

## Out of Scope

Reenvio de instrução pelo reconciliador, que violaria a fronteira. Eleição de líder. Tratamento humano
de operação indeterminada. Reconciliação de lotes, que pertence a outro slice.

## Testing

S2 (reconciliador consulta e converge, sem lógica de transição duplicada no scheduler), S3 (claim
transacional com instâncias concorrentes), S5 (consulta de status por ambos os identificadores), S6
(métrica, log e alerta), S7 (**jornadas 3 e 4**).

## Log

**`fk-servico-credito`.** Migration `V3__reconciliacao_de_efetivacao.sql`: tabela nova
`reconciliacao_efetivacao` (mesmo idioma de bookkeeping do claim de `outbox_entrega`, #0004) com
índice parcial em `proxima_consulta_em WHERE status_reconciliacao='PENDENTE'`; backfill das
`AGUARDANDO_EFETIVACAO` existentes derivando a agenda de `outbox_mensagem.criado_em` — o instante
durável de nascimento da intenção de efetivação, nunca `now()` da migration, preservando a idade
real de solicitações históricas. `ck_historico_tipo` ganhou `EFETIVACAO_INDETERMINADA_REGISTRADA`.
`RegistrarResultadoEfetivacao.registrarIndeterminacao(EfetivacaoId, Instant)` — nova operação única
de saída de `AGUARDANDO_EFETIVACAO` para ignorância, devolvendo `ResultadoIndeterminacao`
(`IndeterminadaAgora`/`JaEstavaIndeterminada`/`JaTerminal`, os dois últimos no-op idempotente).
`ReconciliarEfetivacoes` (Java puro, um episódio por chamada): `TX-A` (claim SKIP LOCKED + lease,
mesmo fencing do #0004) → consulta HTTP ao `CoreLegado` fora de qualquer transação (por
`ProtocoloCore` quando conhecido, por `EfetivacaoId` quando não) → `TX-B` única via `TransacaoPort`
aplicando fencing + resultado + bookkeeping da própria agenda no mesmo commit. O resultado de
`RegistrarResultadoEfetivacao` governa a terminalização: `Concluida`/`JaTerminalIdentica`/
`JaTerminalContraditoria` terminalizam a reconciliação; `SucessoIncoerente`/`ProtocoloDivergente`
**nunca** terminalizam — reagendam como qualquer resposta não autoritativa. Janela esgotada e ainda
`AGUARDANDO_EFETIVACAO` converge em `EFETIVACAO_INDETERMINADA`; a partir daí o polling é de
recuperação de baixa frequência (backoff longo, nunca volta ao curto) até uma conclusão autoritativa
posterior. Alerta (`AlertaOperacionalPort`/`AlertaOperacionalLogAdapter`, WARN + counter) e métrica
disparados só pelo sinal persistido `IndeterminadaAgora`, depois do commit — sem exactly-once entre
PostgreSQL/log/Micrometer, decisão do Owner. `ConsultaStatusEfetivacaoAclAdapter`: mesma "regra de
ouro" do #0004 (erro HTTP nunca produz `FalhaDefinitiva`); `Desconhecida` nunca é tratada como "nunca
aceito" (o simulador perde o store no restart). `JdbcReconciliacaoEfetivacaoAdapter` espelha o
fencing de `JdbcEntregasEfetivacaoAdapter`. TX2 (`CreditoPersistenceOperations.aplicarDecisaoTx2`)
grava a linha de `reconciliacao_efetivacao` no mesmo commit do Outbox, com
`proxima_consulta_em = agora + elegivelApos` e `janela_expira_em = agora + janela` — o construtor
agora falha rápido na inicialização se `elegivel-apos >= janela` (achado do `/code-review`, ver
abaixo).

**`fk-simulador-core-legado`.** `EfetivacoesLegadoStore` ganhou o desfecho consultável
(`marcarProcessada`/`consultarPorIdEft`/`consultarPorNumPrt`) e o control plane de callback
(`ModoCallback.SUPRIMIR` — disparo único; `SUSPENDER` — retido até `liberar` explícito, com
`PendenciaProcessamento` guardada). Endpoint novo `POST /legado/efetivacoes/consulta` (por `idEft`
ou `numPrt`, exatamente um dos dois). Control plane novo: `suprimir-callback`,
`suspender-processamento`, `liberar` (`EfetivacaoControlPlaneController`).

**Testes.** S2 `ReconciliarEfetivacoesTest` 16 (inclui os três adversariais do mapeamento exigidos
pelo Owner — protocolo divergente, limite incoerente, terminal local com resposta contraditória — e
o ciclo completo pós-indeterminação até conclusão autoritativa) e `RegistrarResultadoEfetivacaoTest`
estendido para 18; S3 novo `JdbcReconciliacaoEfetivacaoAdapterTest` 15 (Testcontainers, IP-da-bridge,
AC34 com instâncias concorrentes reais) e `ReconciliacaoBackfillMigrationTest` 1 (prova que o
backfill deriva de `criado_em`, não de `now()`); S4 novo `ConsultaStatusEfetivacaoAclAdapterTest` 19
(WireMock, matriz completa da sealed); S5/S6 estendidos (`SimuladorCoreLegadoSmokeTest`,
`CallbackSegurancaTest`/`CreditoSegurancaTest`/`SubmissaoSegurancaTest` com
`ReconciliacaoEfetivacaoPort` mockado); simulador: `EfetivacoesLegadoStoreTest` 15,
`EfetivacaoLegadoControllerTest` 12, `ProcessadorEfetivacaoLegadoTest` 5. Reator completo (JDK 25):
`fk-servico-credito` 390 testes, `fk-simulador-core-legado` 57 — a única falha é a já documentada em
#0004/#0005 (ausência do binário `docker` CLI no container de build para o smoke test que depende de
`docker build` via `ProcessBuilder`); nenhuma nova falha, nenhuma em módulo não tocado por este
ticket. `fk-app-gerente` (Vitest): 56/56.

**`/code-review` sobre o diff completo — nove angulos, achados corrigidos antes do commit** (um
ângulo adicional falhou por erro de API do provedor, sem substituir sua cobertura). Achados reais,
corroborados por múltiplos ângulos independentes, corrigidos:
(1) `ConsultaStatusEfetivacaoAclAdapter.traduzirSucesso` deixava escapar `IllegalArgumentException`
do compact constructor de `Efetivada` quando `vlrLimEft` era `"0"` ou negativo — violava o próprio
contrato "nunca lança exceção para o chamador"; corrigido para validar antes de construir e cair em
`Indeterminada`.
(2) `EfetivacaoControlPlaneController.liberarProcessamento` chamava `processarPendenciaLiberada`
sincronamente na thread HTTP, e esse caminho termina numa chamada de callback bloqueante —
quebrando o invariante "nunca sincronamente" do resto de `ProcessadorEfetivacaoLegado`; corrigido
para agendar no mesmo `TaskScheduler` de thread única do fluxo normal.
(3) `EfetivacoesLegadoStore.registrarPendencia` sobrescrevia silenciosamente uma pendência suspensa
ainda não liberada, perdendo o `idEft` anterior para sempre; corrigido para lançar
`IllegalStateException` numa segunda tentativa.
(4) `limparCenario` (control plane) não descartava `pendenciasPorConta`, deixando uma pendência
obsoleta sobreviver entre cenários de teste; método novo `limparPendencia`, ligado ao `DELETE`.
(5) `liberarPendencia` limpava `modoCallbackPorConta` mesmo quando não havia pendência — um
`liberar` chamado sobre uma conta com `SUPRIMIR` ainda armado (mas não consumido) apagaria essa
armação como efeito colateral silencioso; corrigido para só limpar o modo quando a pendência de
fato existia.
(6) `EfetivacoesLegadoStore.registrarAceite` populava o índice reverso `numPrt -> idEft` DENTRO do
`computeIfAbsent`, antes da entrada principal ficar visível — uma consulta por `numPrt` concorrente
podia encontrar o índice e reportar "desconhecida" para um `idEft` já aceito; corrigido para
popular o índice reverso só depois do mapa principal já estar visível.
(7) `ReconciliarEfetivacoes.reagendarOuIndeterminar` descartava a anomalia de resultado incoerente
(protocolo divergente ou sucesso incoerente) quando a solicitação já estava
`EFETIVACAO_INDETERMINADA` — `efetivacao_anomalias_total{tipo=RECONCILIACAO_RESULTADO_INCOERENTE}`
nunca incrementava nessa fase; `ResultadoCicloReconciliacao.JaEstavaIndeterminada` ganhou o campo
`incoerente` (mesmo papel de `ConcluidaPorOutroCaminho#contraditoria`) para preservar o sinal.
(8) `elegivel-apos < janela` era um invariante só documentado em comentário, nunca validado — uma
config invertida faria toda solicitação aprovada nascer com a janela já esgotada, convergindo direto
para `EFETIVACAO_INDETERMINADA` sem nenhuma tentativa real de reconciliação; `CreditoPersistenceOperations`
agora falha rápido no construtor (mesmo idioma de `PoliticaRetryEntrega`).
Todos os oito achados acima ganharam teste de regressão dedicado. Achados considerados e
deliberadamente NÃO aplicados, com justificativa: `AlertaOperacionalPort` como porta dedicada para
um único chamador foi sinalizado como abstração prematura, mas é decisão explícita do Owner em Plan
Mode (item 2 das decisões confirmadas); `registrarIndeterminacao` com propagação `MANDATORY` em vez
de `REQUIRED` foi sinalizado, mas o método mirra deliberadamente o mesmo padrão "chamável standalone
ou aninhado" já documentado no Javadoc do método irmão `registrar`; duplicação entre os dois
schedulers e entre os dois fencing checks JDBC é o mesmo padrão de espelhamento do #0004 já aceito
sem objeção nos tickets anteriores; branching de `ModoCallback` dentro de `ProcessadorEfetivacaoLegado.processar`
é uma escolha de posicionamento arquitetural, não um defeito funcional.

**Verificação end-to-end.** `docker compose down -v` + `up --build` (duas vezes — a segunda após os
fixes do `/code-review`): 8/8 serviços saudáveis, sem WARN/ERROR inesperado. Verificação manual via
curl contra o simulador ao vivo (porta `127.0.0.1:8090`, publicada só nos profiles de teste):
consulta por `idEft` desconhecido → `404`; ciclo aceite → `301` (em processamento) → `000`
(processada, `vlrLimEft` correto) por `idEft` e por `numPrt`; cenários `suspender-processamento` +
`liberar` confirmados manualmente antes da bateria formal de e2e. Playwright (`cd e2e && npm test`):
**13/13** — jornada 3 (callback suprimido, conclusão só pela reconciliação, uma única
`EFETIVACAO_SOLICITADA`) e jornada 4 (janela esgota sob processamento suspenso →
`EFETIVACAO_INDETERMINADA`, bloqueio de nova solicitação com aviso de acompanhamento, `liberar`
tardio conclui em `EFETIVADA`) novas; jornadas 1 e 2 ajustadas para a nova apresentação de status
(`apresentacaoDeStatus`, AC37) em vez do enum cru. Com jornada 3 e 4, as quatro jornadas canônicas da
spec fecham. 4 contratos OpenAPI válidos (`node scripts/validar-openapi.mjs`): `fk-simulador-core-legado`
regenerado ao vivo (`info.version` `0004`→`0006`, endpoint `/legado/efetivacoes/consulta` novo);
`fk-servico-credito` não mudou (nenhum endpoint inbound novo neste ticket).

**Fora de escopo, confirmado intacto:** reenvio de instrução pelo reconciliador (violaria a
fronteira #0004/#0006), eleição de líder, tratamento humano de operação indeterminada, reconciliação
de lotes. Superfície HTTP de acompanhamento ao vivo de `EFETIVACAO_INDETERMINADA` (refletir o status
sem reselecionar a conta) permanece fora — nota honesta já no teste da jornada 4 — e pertence a
#0007.

**Ticket fechado.**
