---
id: 0004
title: A aprovação vira instrução entregue ao CoreLegado
state: closed
triage: ready-for-agent
spec: docs/specs/slice-1-straight-through-approval.md
blocked_by: [0003]
assignee:
created: 2026-09-02
closed: 2026-09-03
---

# 0004 — A aprovação vira instrução entregue ao CoreLegado

## Objetivo

A intenção durável que #0003 gravou passa a ser entregue: um dispatcher a lê e envia a instrução ao
`CoreLegado` com `EfetivacaoId` e `limiteChequeEspecialVigenteEsperado`, e o Core responde com aceite
e `ProtocoloCore`, que fica persistido. Aceite não é conclusão — a solicitação permanece em
`AGUARDANDO_EFETIVACAO`.

Nascem aqui a taxonomia de quatro classes da ACL — aceite, transitório, definitivo e indeterminado — e
o caso de uso `RegistrarResultadoEfetivacao`, porque uma recusa definitiva já no aceite precisa
concluir a solicitação. Esse caso de uso é único: #0005 e #0006 acrescentam entradas para ele, nunca
uma segunda implementação da regra de conclusão.

No `simulador-core-legado` entram a operação de efetivação com deduplicação funcional por
`EfetivacaoId` e os cenários de control plane de aceite perdido e indisponibilidade. A deduplicação é
comportamento funcional do Core, não capacidade do control plane. A consulta de status por protocolo
e por `EfetivacaoId` **não** nasce aqui: ela nasce em #0006, que é quem a consome — aqui o protocolo
perdido é recuperado pelo próprio reenvio da instrução.

A fronteira é estrita desde já: o dispatcher **entrega**. Consultar resultado é #0006.

## Acceptance Criteria

- [x] AC11 — perdida a resposta de aceite, o reenvio usa o mesmo `EfetivacaoId`, o Core não aplica a
      alteração de novo, o mesmo `ProtocoloCore` é recuperado e existe uma única operação lógica de
      efetivação
- [x] AC28 — diante de falha transitória, a instrução é reenviada com o mesmo `EfetivacaoId` até o
      limite configurado, com backoff; esgotado o limite o dispatcher para, a solicitação permanece em
      `AGUARDANDO_EFETIVACAO` e **não** transiciona para `FALHA_EFETIVACAO`; nunca se cria uma segunda
      operação lógica
- [x] AC15 — precondição de vigente violada produz `LIMITE_VIGENTE_DIVERGENTE` e `FALHA_EFETIVACAO`,
      sem segunda operação lógica, sem nova `DecisaoCredito` e sem recálculo automático; nova tentativa
      exige nova `SolicitacaoAumentoLimite`
- [x] AC1 (parcial) — a aprovação de #0003 produz exatamente uma instrução entregue, e o
      `ProtocoloCore` fica persistido com a solicitação ainda em `AGUARDANDO_EFETIVACAO`
- [x] AC36 (parcial) — os meters introduzidos aqui, incluindo tempo de permanência em
      `AGUARDANDO_EFETIVACAO`, respeitam a política de cardinalidade da spec

## Blocked by

- #0003 — sem decisão aprovada e sem intenção durável registrada não há o que entregar.

## Out of Scope

O callback, que pertence a #0005. Qualquer consulta de status por iniciativa nossa e o reconciliador,
que pertencem a #0006. `EFETIVACAO_INDETERMINADA` não existe ainda: esgotar retries apenas encerra a
tentativa de entrega e deixa a solicitação onde está.

## Testing

S2 (dispatcher separado do reconciliador por construção), S3 (intenção durável, `messageId` estável e
metadado de tentativa separado dele), S4 (as quatro classes e as patologias que as produzem), S5
(dedup por `EfetivacaoId`, mesmo protocolo recuperado no reenvio, precondição do vigente esperado).

## Log

### 2026-09-03 — franklin.azeredo

Trabalhado inteiramente em Plan Mode antes de qualquer edição, com três rodadas de correção do
Owner sobre o plano (fencing do claim, lifecycle do claim liberando no reagendamento, crash na
última tentativa reservada, precisão sobre a métrica de esgotamento não precisar ser exactly-once
através de crash de processo) até aprovação explícita. Implementação, testes e gates rodaram depois,
autônomos.

**O que foi construído.** `outbox_entrega` (V2, backfill das intenções pré-existentes) como metadado
de entrega separado da intenção imutável (`outbox_mensagem` nunca ganhou coluna nova — decisão já
registrada preservada). Dispatcher `@Scheduled` (`DispatcherEfetivacaoScheduler`) fino sobre o caso
de uso `EntregarInstrucoesEfetivacao` (Java puro, sem Spring): claim unitário por
`SELECT ... FOR UPDATE SKIP LOCKED` com fencing token (`claim_id`, novo a cada reclamação) — toda
escrita de resultado (aceite/transitório/definitivo/indeterminado) verifica o fencing sob lock fresco
antes de qualquer efeito, e claim obsoleto é descartado por inteiro, nem métrica de resultado.
Backoff exponencial com jitter persistido em `proxima_tentativa_em` (nunca `Thread.sleep`).
`EfetivacaoLegadoAclAdapter` traduz a taxonomia de quatro classes (aceite/transitório/definitivo/
indeterminado) a partir do `COD-RET` host — erro HTTP técnico nunca produz `FALHA_EFETIVACAO` pelo
status, só um `COD-RET` definitivo conhecido produz essa classe. `RegistrarResultadoEfetivacao`
nasce como caso de uso único de conclusão (ADR-0009): a variante deste ticket só cobre falha
definitiva; a variante sucesso é #0005. No `simulador-core-legado`: `POST /legado/efetivacoes` com
dedupe funcional por `idEft` e o control plane de cenários (`PERDER_ACEITE`, `INDISPONIVEL(n)`,
ADR-0018), ativo só em `local`/`demo`/`test`.

**Testes.** S1 `PoliticaRetryEntrega` 7; S2 `EntregarInstrucoesEfetivacaoTest` 6,
`RegistrarResultadoEfetivacaoTest` 3; S3 `JpaEntregasEfetivacaoAdapterTest` 14 (inclui os dois
adversariais de fencing exigidos pelo Owner — worker com lease expirado nunca sobrescreve o que um
worker mais novo já persistiu, em ambas as ordens — e os dois de crash na última tentativa
reservada) mais `JpaSolicitacoesAumentoLimiteAdapterTest` de #0003 revalidado (22, intacto); S4
`EfetivacaoLegadoAclAdapterTest` 18 (matriz completa da taxonomia, incluindo COD-RET 998); S5
`SimuladorCoreLegadoSmokeTest` 9 contra o simulador real construído do próprio Dockerfile; S6
`CreditoSegurancaTest` 19 e `SubmissaoSegurancaTest` 24; simulador `EfetivacaoLegadoControllerTest`
8; `ArchitectureTest` 15. Reator completo (JDK 25, `./mvnw clean verify`): **473 testes**, únicas
falhas as três descritas abaixo — nenhuma em código deste ticket.

**Achado de ambiente (infraestrutura de teste, não defeito de produção).** Neste ambiente de
execução (Maven rodando num container com o socket do Docker montado, "Docker-outside-of-Docker"),
o mapeamento de porta publicada (`host:portaMapeada`, via gateway da bridge) mostrou-se
consistentemente inalcançável a partir de outro container, mesmo com o container de destino
saudável e a porta corretamente publicada — verificado em três testes pré-existentes e não tocados
por este ticket, em três módulos: `ClienteLegadoAclAdapterTest` (timeout de 500ms colidindo com a
latência real do ambiente, `fk-servico-carteira-clientes`, #0001), `CreditoLegadoAclAdapterTest`
(mesmo padrão de timeout apertado, `fk-servico-credito`, #0001) e `BffSegurancaTest` (Testcontainers
Redis: log confirma o container saudável, só a wait-strategy padrão por porta publicada não alcança
o container irmão). Nenhum dos três foi alterado — pertencem a tickets já fechados e o defeito é do
ambiente de execução desta sessão, não do código. Testes novos deste ticket que dependem de
Testcontainers resolvem o IP do container na rede bridge diretamente (`getContainerInfo().
getNetworkSettings()`), contornando o problema pela raiz.

**`/code-review` sobre o diff completo — achados corrigidos antes do commit.** Quatro ângulos em
paralelo (reuso/simplificação/eficiência; altitude arquitetural e convenções do CLAUDE.md; leitura
fresca de correção; pitfalls de linguagem e correção de wrapper). Confirmados e corrigidos:

1. **`agora` obsoleto atravessando a chamada HTTP** — `EntregarInstrucoesEfetivacao` recebia um
   único `Instant` usado tanto para o claim quanto, sem recapturar, para calcular
   `proximaTentativaEm` e a permanência em `AGUARDANDO_EFETIVACAO` depois do retorno do Core —
   subestimando backoff e métrica pela duração real da chamada. Corrigido injetando `Clock` no caso
   de uso e capturando o instante duas vezes (antes do claim, depois do HTTP).
2. **Um episódio que lança exceção matava o tick inteiro** silenciosamente, sem contar nem seguir
   para o resto do lote. `DispatcherEfetivacaoScheduler` agora captura por episódio, conta
   (`efetivacao_erros_inesperados_total`) e continua.
3. **Timeout compartilhado entre o caminho síncrono (leitura de limite) e o dispatcher em
   background** classificava um Core lento-mas-saudável como `FalhaTransitoria` e queimava
   tentativas do orçamento de retry. `EfetivacaoLegadoAclAdapter` ganhou `RestClient` próprio
   (`efetivacaoLegadoRestClient`, PT2S/PT5S) desacoplado do bean síncrono.
4. **`efetivacao_entregas_total` registrado com dois conjuntos de tag-key diferentes** (`classe`
   sozinho na maioria dos ramos; `classe`+`motivo` em `FALHA_DEFINITIVA`) — quebraria registries
   estritos (Prometheus) na primeira vez que fossem ligados. Motivo movido para um contador próprio
   (`efetivacao_falhas_definitivas_total`).
5. **`RegistrarResultadoEfetivacao` era código morto** — o Javadoc afirmava ser o único caminho de
   conclusão, mas `JpaEntregasEfetivacaoAdapter` chamava `ResultadoEfetivacaoPort` diretamente,
   nunca o caso de uso. Religado corretamente; ganhou `@Bean` próprio.
6. **Dois `switch` não exaustivos** (`aplicarTransitoria`/`aplicarIndeterminada` usavam ternário
   comparando com uma constante) viraram `switch` exaustivo sobre o enum, como `aplicarAceite` já
   fazia — um valor novo no enum agora força decisão em vez de cair no `else` por acidente.
7. **Simulador: `consumirCenario` rodava antes do dedup por `idEft`** — um reenvio de `idEft` já
   aceito podia consumir um cenário de indisponibilidade armado depois e devolver 503 espúrio,
   quebrando a garantia de reenvio idempotente documentada no contrato. Invertida a ordem.
8. **`codRet` 998 (indisponibilidade de negócio) documentado incompleto no OpenAPI** — o plano
   aprovado já definia esse código na taxonomia (seção 6) e como cenário obrigatório de S4 (seção
   10); só a descrição do contrato ficou incompleta. Adicionado ao enum, com nota explícita de que
   este simulador nunca o emite (seus cenários modelam indisponibilidade como HTTP real).
9. Cosméticos: `causeContains` (checagem de causa-raiz, sem taxonomia) deduplicado entre
   `EfetivacaoLegadoAclAdapter` e `CoreLegadoCall` no mesmo pacote; `reclamarProxima` trocou
   `list().get(0)` por `.optional()`, como o resto da classe já fazia; comentário explícito sobre a
   dependência do fencing lock em `registrarAceite`; `EfetivacoesLegadoStore.consumirCenario` não
   dispara mais uma indisponibilidade quando configurado com `vezes=0`; comentário na migration V2
   sobre a colisão de `numPrt` entre restart do simulador e `credito_db` não resetado.

**Contradição documental resolvida (não decisão nova).** ADR-0009 e o glossário de
`docs/contextos/credito/CONTEXT.md` ainda diziam que `messageId` "pode mudar a cada
tentativa/reenvio", enquanto a spec (revisão de 2026-09-02, registrada no próprio Log dela) já dizia
o oposto — `EfetivacaoId` e `messageId` igualmente estáveis — e #0003/#0004 já implementavam a
versão da spec (`outbox_entrega.message_id` é PK/FK 1:1, nunca muda entre tentativas). Corrigidos os
dois para refletir o que já está em produção, sem mudar comportamento algum.

Riscos aceitos e não alterados (fora de escopo, documentados no ponto de uso): control plane de
cenários protegido só por profile Spring, sem enforcement de build (ADR-0018, decisão já tomada);
`outbox_mensagem`/`EntregasEfetivacaoPort` tipados a `IntencaoEfetivacao` — extensível apenas quando
um segundo tipo de mensagem existir; cenário `PERDER_ACEITE` registra aceite sem repetir as
validações funcionais (intencional — é controle de teste, não comportamento do host).

**Verificação end-to-end.** `docker compose down -v` + `up --build`: 8/8 serviços saudáveis;
submissão aprovada confirmada em `credito_db` com `protocolo_core` preenchido, status
`AGUARDANDO_EFETIVACAO`, `outbox_entrega` `ACEITA`/`ACEITE`, histórico com `EFETIVACAO_SOLICITADA` e
`INSTRUCAO_ACEITA_PELO_CORE` uma vez cada. Playwright (`cd e2e && npm test`): **11/11**, nenhuma
jornada nova (fora de escopo deste ticket). 4 contratos OpenAPI válidos
(`node scripts/validar-openapi.mjs`), incluindo `/legado/efetivacoes` novo.

**Fora de escopo, confirmado intacto:** #0005 (callback, variante sucesso, `EFETIVADA`) e #0006
(reconciliação, `EFETIVACAO_INDETERMINADA`, consulta de status) não foram implementados.

**Ticket fechado.**
