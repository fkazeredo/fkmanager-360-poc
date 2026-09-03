---
id: 0003
title: Gerente registra a solicitação e recebe a decisão automática
state: open
triage: ready-for-agent
spec: docs/specs/slice-1-straight-through-approval.md
blocked_by: [0002]
assignee:
created: 2026-09-02
---

# 0003 — Gerente registra a solicitação e recebe a decisão automática

## Objetivo

O gerente registra a `ManifestacaoCliente` e o `LimiteSolicitado`, e recebe a decisão na própria
resposta. É a fronteira comportamental `submissão → decisão automática`, e o coração do slice.

Entra tudo o que essa fronteira exige: a ordem de avaliação em nove passos, com as validações locais
antes da autorização e o optimistic check **antes** da comparação com o vigente; `Idempotency-Key`
com fingerprint canônico; unicidade não terminal por `ContaCorrente` garantida pelo banco sob
concorrência real; montagem e congelamento do `ContextoDecisaoCredito` com os `DadosCreditoCore` e sua
procedência; `PoliticaCredito v1` nas quatro faixas; `DecisaoCredito` com `MotivoDecisaoCredito`; e a
fronteira TX1/TX2 sem nenhuma chamada remota com transação aberta.

`credito_db` nasce aqui, com credencial e migrations próprias, porque este é o primeiro comportamento
com estado durável de `Credito`.

**Sobre a intenção de efetivação**: o objetivo **não** é construir infraestrutura genérica de Outbox.
Materialize apenas o mecanismo mínimo que garante atomicamente, em TX2, `DecisaoCredito` aprovada mais
`AGUARDANDO_EFETIVACAO` mais `EfetivacaoId` mais a intenção durável de efetivação. Ninguém consome
essa intenção neste ticket — o dispatcher nasce em #0004. Não antecipe abstrações de transporte,
destino, Kafka ou RabbitMQ.

## Acceptance Criteria

- [x] AC27 — `canalManifestacao` obrigatório e restrito; `observacao` opcional, com trim, recusada
      acima de 500 caracteres, e vazia após trim equivalente a ausência; `OrigemSolicitacao` gravada
      como `CLIENTE` independentemente do payload, e um comando que tente enviar origem ou `clienteId`
      não altera o que é gravado
- [x] AC5 — com `limiteVigenteVisto` coerente com o Core, `limiteSolicitado` que não é estritamente
      maior devolve `422` sem criar solicitação, decisão, intenção de efetivação ou histórico;
      `limiteSolicitado` não positivo também é `422`, detectado antes de qualquer chamada ao Core
- [x] AC6 — limite visto desatualizado devolve `409` e não cria solicitação; o caso decisivo é visto
      5.000, Core em 6.000, pedido de 5.500 → `409`, **nunca** `422`; o front atualiza o limite, gera
      nova `Idempotency-Key` e a nova tentativa é processada
- [x] AC8 — replay com mesma chave e mesmo fingerprint devolve `200` e a mesma `solicitacaoId`, sem
      nova solicitação, decisão, intenção de efetivação ou entrada de histórico
- [x] AC9 — mesma chave com fingerprint diferente devolve `422` e a operação original fica inalterada
- [x] AC10 — duas submissões concorrentes para a mesma conta: apenas uma cria solicitação não
      terminal, a outra recebe `409`, e nunca existem duas não terminais para a mesma conta.
      Concorrência real contra PostgreSQL, não mock de repositório
- [x] AC7 — resposta inválida do Core → `502`; indisponibilidade → `503`; timeout → `504`; em todos,
      nada é persistido parcialmente e o retry com mesmo payload e mesma chave prossegue depois
- [x] AC2 — conta não elegível: solicitação persistida, `REJEITADA` com `CONTA_NAO_ELEGIVEL`, nenhuma
      intenção de efetivação e nenhuma chamada de efetivação ao Core
- [x] AC3 — perfil de risco incompatível: `REJEITADA` com `PERFIL_RISCO_INCOMPATIVEL`, e a API não
      expõe a `ClassificacaoRiscoCreditoBase`
- [x] AC4 — fora da política automática: `REJEITADA` com `FORA_DA_POLITICA_AUTOMATICA`, motivo
      distinto dos dois anteriores
- [x] AC32 — o `ContextoDecisaoCredito` persistido carrega a procedência lógica dos `DadosCreditoCore`
      e o instante da consulta; alterar o Core depois não altera o que a decisão histórica apresenta
- [x] AC33 — `clienteId`, `contaId` e `originadorId` ficam na `SolicitacaoAumentoLimite`, não no
      contexto; a evidência de autorização não integra o contexto; e reaplicar a política sobre o
      contexto persistido devolve a mesma decisão e o mesmo motivo, sem acesso a nada fora dele
- [x] AC31 — dada uma solicitação anterior terminal para a mesma conta, uma nova submissão com os
      mesmos valores e `Idempotency-Key` nova cria uma nova `SolicitacaoAumentoLimite`
- [x] AC18 — interrompida a operação após o commit de TX1 e antes de TX2, a solicitação permanece em
      `SOLICITADA` com contexto completo, e a retomada produz a decisão a partir desse contexto
      congelado, sem nova consulta a `CarteiraClientes` ou ao `CoreLegado`
- [x] AC1 (parcial) — aprovação registra `DecisaoCredito = APROVADA` com `versaoPoliticaCredito = v1`,
      transiciona para `AGUARDANDO_EFETIVACAO` e deixa exatamente **uma** intenção durável de
      efetivação. A conclusão em `EFETIVADA` pertence a #0005
- [x] AC29 (parcial) — na resposta e na tela, o `limiteSolicitado` aparece marcado como pendente e o
      `LimiteChequeEspecialVigente` continua sendo o confirmado pelo Core. O fechamento após
      `EFETIVADA` pertence a #0005
- [x] AC37 (parcial) — `FORA_DA_POLITICA_AUTOMATICA` renderiza a semântica exata e não afirma risco
      elevado, problema cadastral nem inelegibilidade permanente; `502`, `503` e `504` renderizam uma
      única mensagem de indisponibilidade com ação de repetir. A mensagem de indeterminação pertence a
      #0006
- [x] AC36 (parcial) — os meters introduzidos aqui usam apenas labels de baixa cardinalidade e nenhum
      carrega `clienteId`, `contaId`, `solicitacaoId`, `protocoloCore` ou `correlationId`

## Blocked by

- #0002 — a leitura do vigente pela ACL de `Credito` e a autorização de recurso precedem a submissão.

## Out of Scope

O dispatcher e qualquer entrega da instrução ao Core, que pertencem a #0004. Abstração genérica de
transporte ou de destino no registro da intenção. Consulta e listagem das solicitações, que pertencem
a #0007.

## Testing

S1 (política inteira, transições, transição inválida, imutabilidade do contexto), S2 (orquestração e
contexto incompleto), S3 (unicidade sob corrida real, idempotência persistente, atomicidade de TX2,
histórico sem duplicata), S4 (`502`/`503`/`504` na montagem), S6 (status codes e contrato da borda).

## Log

### 2026-09-02 — franklin.azeredo

Implementação concluída e verificada contra a stack real (`docker compose up`), não apenas por
teste isolado. Este ticket entrega a fronteira `submissão → decisão automática` inteira — as três
etapas anteriores desta feature (domínio, aplicação/persistência, borda web/BFF/OpenAPI) chegaram
sem commit; esta é a primeira e única entrada de Log e também o primeiro commit da feature.

**O que foi construído no backend.** Schema mínimo em `V1__criar_estado_duravel_de_credito.sql`
(`solicitacao_aumento_limite`, `contexto_decisao_credito`, `decisao_credito`,
`registro_idempotencia`, `outbox_mensagem`, `historico_solicitacao`), dinheiro sempre `BIGINT` de
centavos, toda constraint nomeada. Fronteira TX1/TX2 sem nenhuma chamada remota com transação
aberta: TX1 persiste a `SolicitacaoAumentoLimite` em `SOLICITADA` junto do `ContextoDecisaoCredito`
congelado e do registro de idempotência; TX2, fora de qualquer rede, roda o `MotorDecisaoCredito`
sobre esse contexto e aplica a decisão atomicamente (`decisao_credito` + status + `efetivacaoId` +
uma linha de Outbox no mesmo commit — rejeição não gera nenhuma das duas últimas). A idempotência é
resolvida por `(originadorId, Idempotency-Key)` com fingerprint canônico calculado sobre o comando
normalizado (SHA-256, prefixo de versão, `observacao` length-prefixed); o guardrail de concorrência
mais delicado — duas requisições com a mesma key, mesmo fingerprint e mesma conta colidindo primeiro
no índice `uk_solicitacao_nao_terminal_por_conta` — é resolvido relendo o registro de idempotência
depois do rollback e classificando por essa releitura, nunca pelo statement que efetivamente falhou:
do contrário a perdedora de um replay seria erroneamente relatada como
`SOLICITACAO_NAO_TERMINAL_EXISTENTE`. A `PoliticaCredito v1` resolve pela `versaoPoliticaCredito`
**congelada no contexto**, nunca pela vigente — inclusive na retomada de uma `SOLICITADA`
interrompida entre TX1 e TX2, que roda sem nenhuma chamada remota nova. Scopes por operação:
`credito.leitura` para o `GET` do vigente, `credito.escrita` para o `POST` da submissão, com duas
registrations de Token Exchange distintas no BFF (least privilege por operação, não por serviço). O
envelope de erro carrega `codigo` estável do domínio em todo `ProblemDetail` desta fronteira,
inclusive nos handlers de 403/404 que já existiam desde #0002.

**Correção de regressão herdada de uma etapa anterior desta mesma feature.** O
`GlobalExceptionHandler` do `bff-gerente` precisava de uma allow-list de `(status, codigo)` para os
status novos que #0003 introduz no encaminhamento (400/409/422 — nunca existiram antes neste
proxy), mas uma primeira versão dessa allow-list também exigia `codigo` conhecido para 403 e 404.
Isso teria regredido uma garantia já provada desde #0002 (AC23): `servico-carteira-clientes` nunca
publicou `codigo` em 403/404, e continua sem publicar — não é tocado por este ticket. Corrigido
para que 403/404 **sempre** atravessem com o mesmo status, com `codigo` quando o upstream publica
(caso de `servico-credito`, a partir de agora) e sem quando não (caso de
`servico-carteira-clientes`, inalterado). A exigência de `codigo` conhecido na allow-list vale
somente para os status genuinamente novos no encaminhamento do BFF.

**Frontend Angular — formulário de submissão e o lifecycle da `Idempotency-Key`.** Estendido
`AtendimentoComponent` (não criado componente filho) com o formulário de manifestação
(`canalManifestacao`, `observacao`, `limiteSolicitado` em reais) e o estado de submissão. O
guardrail mais importante desta etapa: **uma manifestação semântica → uma `Idempotency-Key`**, nunca
uma key por tentativa HTTP. A key é cunhada (`crypto.randomUUID()`) só dentro do método de
submissão, e só quando `idempotencyKeyAtual` (estado de controle interno, deliberadamente não um
`signal`) é `null`; os três handlers `(input)`/`(change)` do formulário são os únicos pontos que a
zeram por edição do gerente, e não há `effect()` observando os campos — a decisão de cunhar ou
reusar fica inteiramente legível dentro do próprio método de submissão. Reenvios da mesma
manifestação (retry após `502`/`503`/`504`, ou após `409 IDEMPOTENCIA_EM_PROCESSAMENTO`/
`409 SOLICITACAO_NAO_TERMINAL_EXISTENTE`) reusam a mesma key porque nada os zera; um desfecho
terminal (`200`/`201`) ou um `409 LIMITE_VIGENTE_DESATUALIZADO` zeram a key e, neste último caso,
disparam um novo `GET` do atendimento para atualizar o `limiteVigenteVisto` que a próxima tentativa
usa — sem reenvio automático, o gerente decide. A conversão reais→centavos
(`parseReaisParaCentavos`) separa a parte inteira da fracionária como strings e nunca passa por
`parseFloat`/ponto flutuante (ADR-0005); entrada vazia, não numérica ou com mais de duas casas
decimais é `null`, tratada como validação local antes de qualquer chamada de rede. As mensagens
seguem a spec literalmente: `FORA_DA_POLITICA_AUTOMATICA` nunca afirma risco ou problema cadastral;
`CONTA_NAO_ELEGIVEL` e `PERFIL_RISCO_INCOMPATIVEL` não revelam a classificação bruta; aprovação
mostra o vigente confirmado pelo Core e o solicitado marcado como pendente **simultaneamente**, e a
presença de `limiteSolicitadoPendenteDeEfetivacao` (verificada com `!== undefined`, nunca
truthiness) é o único sinal — nunca um teste que trataria um pendente de R$ 0,00 como ausente.

**Dois bugs reais, só visíveis contra a stack real — nenhum dos dois pego por `mvn test`/`ng
test`, ambos pegos por Playwright.** Documentados aqui em vez de maquiados, como o processo exige.

1. *Formulário reiniciava a SPA inteira ao submeter.* O template usava
   `<form (ngSubmit)="submeter()">`, mas `(ngSubmit)` só existe porque `NgForm` (parte do
   `FormsModule`) intercepta o evento nativo `submit` e chama `preventDefault()` — e este app nunca
   importou `FormsModule` (todo o resto do arquivo já usa `[value]`/`(input)` manuais, sem
   `ngModel`). Sem a diretiva, o binding não interceptava nada: o clique disparava a submissão
   nativa do HTML, que navega para a própria URL (sem `action`) e reinicia o Angular do zero —
   sessão preservada (cookie), mas de volta à tela de carteira. Corrigido trocando por
   `(submit)="onFormSubmit($event)"`, que é o evento nativo do DOM (funciona em qualquer elemento,
   sem módulo algum), com `event.preventDefault()` explícito antes de `submeter()`.
2. *Resposta do proxy do BFF virava `502` da própria nginx, nunca chegando ao Angular.*
   `SolicitacaoAumentoLimiteProxyController` devolvia o `ResponseEntity<String>` inteiro que o
   `RestClient` recebeu de `servico-credito`, headers hop-by-hop incluídos. Quando a resposta
   upstream vinha com `Transfer-Encoding: chunked`, esse header ia junto, e o container do
   `bff-gerente` **também** escrevia o seu próprio `Transfer-Encoding: chunked` por cima —
   resultando numa resposta HTTP com o header duplicado, que a `nginx` da frente (corretamente)
   recusa com `502` e a mensagem de log `upstream sent duplicate header line`. Invisível em
   `SolicitacaoAumentoLimiteProxyTest` porque `MockMvc` não serializa para um socket HTTP real.
   Corrigido reconstruindo a resposta com apenas status + `Content-Type` + corpo, nunca repassando
   o `HeaderMap` de upstream — Content-Length/Transfer-Encoding da resposta final ficam a cargo do
   próprio container a partir do corpo que de fato é escrito.

**Testes.** Java: 406 testes, `./mvnw -Dmaven.compiler.release=21 clean test` sobre o reactor
inteiro (13 `fk-servidor-autorizacao`, 95 `fk-servico-carteira-clientes`, 248 `fk-servico-credito`,
35 `fk-bff-gerente`, 15 `fk-simulador-core-legado`), `BUILD SUCCESS`, sem falha nem erro. Angular:
34 testes (`ng test --watch=false`), 14 preexistentes de `atendimento.spec.ts` mais 20 novos em
`atendimento-submissao.spec.ts` — companheiro dedicado ao lifecycle da key e à submissão, para não
misturar a matéria de #0002 com a de #0003 — cobrindo explicitamente: mesma key após `503`/timeout;
key nova ao editar `limiteSolicitado`/`observacao` após uma tentativa; key nova e novo `GET` do
atendimento após `409 LIMITE_VIGENTE_DESATUALIZADO`; **nenhum** novo `GET` e mesma key preservada
após `409 SOLICITACAO_NAO_TERMINAL_EXISTENTE`/`409 IDEMPOTENCIA_EM_PROCESSAMENTO`; key nova após
desfecho de sucesso; aprovação com vigente+pendente simultâneos; rejeição sem marcação de pendente;
parsing de reais para centavos nos casos válidos e inválidos. 4 contratos OpenAPI validados
(`node scripts/validar-openapi.mjs`).

**Verificação end-to-end.** `docker compose down -v` (obrigatório: o script novo
`infra/postgres-init/02-credito.sh` só roda em volume vazio) seguido de `docker compose up --build`:
rebuild das 6 imagens, cold start dos 8 serviços, todos saudáveis; logs de todos os serviços sem
erro nem warning inesperado, com atenção a `servico-credito` (Flyway aplicando
`V1__criar_estado_duravel_de_credito.sql` com a credencial de migrator) e a `postgres`
(`02-credito.sh` criando `credito_db`, `credito_migrator` e `credito_app` sem erro). Playwright
contra essa stack (`cd e2e && npm test`): **11 testes, todos passando** — as nove jornadas 1
preexistentes (login, sessão, carteira, seleção de conta/limite, CSRF, restart do BFF), a jornada 1
estendida (novo `test(...)` cobrindo submissão na conta `10002` do cliente `1`, dentro da política
automática, com o vigente confirmado pelo Core e o solicitado pendente exibidos simultaneamente,
`AGUARDANDO_EFETIVACAO`, `APROVADA`) e a jornada 2 nova
(`e2e/tests/jornada2-rejeicao-automatica.spec.ts`, cliente `4`/conta `10005` BLOQUEADA, `REJEITADA`
com `CONTA_NAO_ELEGIVEL`, sem marcação de pendente) — nenhuma jornada além das quatro canônicas da
spec foi criada. Os dois bugs acima só apareceram nesta verificação Playwright; corrigidos, a stack
foi reconstruída do zero (`down -v` + `up --build`) e a suíte inteira rodou verde de novo, sem
reaproveitar estado de uma tentativa anterior.

**Status nesta data: aguardando code review.**

### 2026-09-03 — franklin.azeredo

Refactoring arquitetural transversal, decidido pelo Owner e aplicado sobre o estado acumulado de
#0001, #0002 e #0003 **antes** do code review. Não é rewrite: nenhum Acceptance Criteria, contrato,
transação, invariante de concorrência ou comportamento observável mudou. O objeto é infraestrutura.

**A fronteira que define o refactor.** `domain/**` e `application/**` ficaram byte-idênticos nos dois
serviços — verificado por `git diff` vazio nesses caminhos ao final. As ports são o contrato
congelado: `SolicitacoesAumentoLimitePort`, `RegistroIdempotenciaPort` e `VinculosCarteiraPort`
mantêm nome, pacote e assinatura. Uma versão anterior do plano propunha fundir as duas primeiras num
`CreditoPersistencePort`; o Owner vetou — *"em nenhum momento pedi pra invadir o hexágono, pedi pra
usar JPA e Lombok e mudar uma porta"* —, e a fusão foi retirada. O efeito colateral é o melhor
oráculo disponível: todos os testes S1/S2 compilaram e passaram sem uma linha de edição.

**JPA como padrão, com os testes decidindo onde ele não serve.** `CarteiraClientes` trocou dois
SQLs manuais por `existsByGerenteIdAndClienteId` e `findByGerenteIdOrderByIdAsc(Pageable)` — query
derivation pura, nenhum SQL restante. `Credito` ganhou seis `@Entity`, cinco repositories e um
fragment transacional. TX1 foi implementado primeiro em JPA e submetido imediatamente aos S3 de
concorrência, conforme a instrução do Owner de não pré-decidir a tecnologia: **passou de primeira**
nas três corridas reais e nos testes de replay, e o resultado é mais legível que o JDBC anterior —
quatro blocos de `persist`+`flush`, sem bind manual de parâmetro. TX1 fica JPA.

Duas descobertas que só apareceram por rodar os S3 contra PostgreSQL real, e que teriam passado
despercebidas com mock: `repository.save()` é ambíguo para as três entidades cujo identificador é
atribuído pela aplicação antes do save (a detecção de "entity nova" do Spring Data olha se o `@Id` é
nulo, e não é — `save()` viraria `merge()`, com um SELECT redundante no trecho mais sensível de TX1);
e a tradução de exceção exige o estereótipo `@Repository`, não `@Component` — sem ele,
`ConstraintViolationException` bruta do Hibernate escaparia em vez de
`DataIntegrityViolationException`, quebrando silenciosamente o `catch` que é o guardrail inteiro de
TX1.

**Onde SQL permaneceu, e por quê.** Uma única ocorrência: o `SELECT ... FOR UPDATE NOWAIT` de TX2,
em `JdbcClient`. A razão é concreta e está documentada no ponto de uso — o comportamento de exceção
foi verificado empiricamente (`55P03` chega como `UncategorizedSQLException`, não como
`CannotAcquireLockException`), e trocar por `@Lock(PESSIMISTIC_WRITE)` mudaria o tipo devolvido pelo
provider sem que nenhum teste de unidade percebesse. O lock é adquirido antes de qualquer escrita
JPA na transação, então não há hazard de flush entre os dois mecanismos.

**Lombok fora do hexágono.** Vinte classes em `adapter/**` e `config/**` receberam
`@RequiredArgsConstructor` ou `@Slf4j`; entidades JPA usam `@Getter` + `@NoArgsConstructor(PROTECTED)`,
nunca `@Data`. `domain/**` e `application/**` continuam sem Lombok, e isso deixou de ser convenção:
`lombok.addLombokGeneratedAnnotation` marca cada membro gerado com `@lombok.Generated`, e uma regra
ArchUnit por serviço falha se essa anotação aparecer dentro do hexágono. A regra foi mantida porque
se provou falsificável de verdade — anotar temporariamente uma classe de `application/` deixa o build
vermelho, o que foi verificado e revertido nos dois módulos.

Um achado fora do inventário: `CarteiraClientesAdapter` resolvia seu `RestClient` por coincidência
entre o nome do parâmetro do construtor e o nome do `@Bean`, num módulo que tem dois `RestClient`.
`@RequiredArgsConstructor` gera o parâmetro com o nome do **campo**, o que teria quebrado essa
resolução implícita em silêncio. Corrigido com `@Qualifier` explícito no campo — que
`lombok.copyableAnnotations` propaga para o construtor gerado.

**Frontend em `https://localhost:4200`.** nginx passou a publicar 4200 com TLS; o bloco `listen 80`
saiu. Nenhuma mudança de arquitetura de segurança: same-origin, cookie `Secure`, CSRF, PKCE e Token
Exchange idênticos — certificado X.509 nunca amarrou porta, então o mesmo `CN=localhost` serve. É
HTTPS e não HTTP porque o AC19 prova `secure: true` no cookie de sessão contra a stack real; servir
em HTTP puro seria regressão de garantia já provada. As três alternativas foram apresentadas ao Owner
antes de qualquer alteração, e esta foi a escolhida.

**Um contorno foi desfeito no caminho.** A migração deixou `@Lazy` em `CreditoPersistenceOperations`
para que os testes S6 — que sobem o contexto sem `DataSource` — não tentassem instanciá-lo. Isso é
código de produção moldado por setup de teste. Substituído por `@MockitoBean` do fragment nos dois
testes S6: a exclusão pertence a quem escolheu subir sem banco.

**Decisão registrada em ADR.** `docs/adr/0023-jpa-como-padrao-de-persistencia-lombok-fora-do-hexagono.md`
fixa as três: Spring Data JPA como padrão com SQL como exceção justificada e documentada no ponto de
uso; Lombok permitido fora do hexágono, sem `@Data` em entity; frontend local em 4200 sobre TLS.
Flyway continua soberano (`ddl-auto: validate`, nunca `create`/`update`), `open-in-view: false`, e a
separação de credenciais migrator/DML de ADR-0014 intacta.

**Dívida deliberada.** O briefing original pedia também revisar "ports que são apenas wrappers de
CRUD" e consolidá-las; o Owner restringiu o escopo depois, e a revisão não foi feita.
`RegistroIdempotenciaPort` continua sendo uma port de leitura única — cabe reabrir, mas não cabia em
"usar JPA e Lombok e mudar uma porta".

**Status nesta data: aguardando code review, agora sobre o delta já refatorado.**
