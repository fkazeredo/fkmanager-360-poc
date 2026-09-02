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

- [ ] AC27 — `canalManifestacao` obrigatório e restrito; `observacao` opcional, com trim, recusada
      acima de 500 caracteres, e vazia após trim equivalente a ausência; `OrigemSolicitacao` gravada
      como `CLIENTE` independentemente do payload, e um comando que tente enviar origem ou `clienteId`
      não altera o que é gravado
- [ ] AC5 — com `limiteVigenteVisto` coerente com o Core, `limiteSolicitado` que não é estritamente
      maior devolve `422` sem criar solicitação, decisão, intenção de efetivação ou histórico;
      `limiteSolicitado` não positivo também é `422`, detectado antes de qualquer chamada ao Core
- [ ] AC6 — limite visto desatualizado devolve `409` e não cria solicitação; o caso decisivo é visto
      5.000, Core em 6.000, pedido de 5.500 → `409`, **nunca** `422`; o front atualiza o limite, gera
      nova `Idempotency-Key` e a nova tentativa é processada
- [ ] AC8 — replay com mesma chave e mesmo fingerprint devolve `200` e a mesma `solicitacaoId`, sem
      nova solicitação, decisão, intenção de efetivação ou entrada de histórico
- [ ] AC9 — mesma chave com fingerprint diferente devolve `422` e a operação original fica inalterada
- [ ] AC10 — duas submissões concorrentes para a mesma conta: apenas uma cria solicitação não
      terminal, a outra recebe `409`, e nunca existem duas não terminais para a mesma conta.
      Concorrência real contra PostgreSQL, não mock de repositório
- [ ] AC7 — resposta inválida do Core → `502`; indisponibilidade → `503`; timeout → `504`; em todos,
      nada é persistido parcialmente e o retry com mesmo payload e mesma chave prossegue depois
- [ ] AC2 — conta não elegível: solicitação persistida, `REJEITADA` com `CONTA_NAO_ELEGIVEL`, nenhuma
      intenção de efetivação e nenhuma chamada de efetivação ao Core
- [ ] AC3 — perfil de risco incompatível: `REJEITADA` com `PERFIL_RISCO_INCOMPATIVEL`, e a API não
      expõe a `ClassificacaoRiscoCreditoBase`
- [ ] AC4 — fora da política automática: `REJEITADA` com `FORA_DA_POLITICA_AUTOMATICA`, motivo
      distinto dos dois anteriores
- [ ] AC32 — o `ContextoDecisaoCredito` persistido carrega a procedência lógica dos `DadosCreditoCore`
      e o instante da consulta; alterar o Core depois não altera o que a decisão histórica apresenta
- [ ] AC33 — `clienteId`, `contaId` e `originadorId` ficam na `SolicitacaoAumentoLimite`, não no
      contexto; a evidência de autorização não integra o contexto; e reaplicar a política sobre o
      contexto persistido devolve a mesma decisão e o mesmo motivo, sem acesso a nada fora dele
- [ ] AC31 — dada uma solicitação anterior terminal para a mesma conta, uma nova submissão com os
      mesmos valores e `Idempotency-Key` nova cria uma nova `SolicitacaoAumentoLimite`
- [ ] AC18 — interrompida a operação após o commit de TX1 e antes de TX2, a solicitação permanece em
      `SOLICITADA` com contexto completo, e a retomada produz a decisão a partir desse contexto
      congelado, sem nova consulta a `CarteiraClientes` ou ao `CoreLegado`
- [ ] AC1 (parcial) — aprovação registra `DecisaoCredito = APROVADA` com `versaoPoliticaCredito = v1`,
      transiciona para `AGUARDANDO_EFETIVACAO` e deixa exatamente **uma** intenção durável de
      efetivação. A conclusão em `EFETIVADA` pertence a #0005
- [ ] AC29 (parcial) — na resposta e na tela, o `limiteSolicitado` aparece marcado como pendente e o
      `LimiteChequeEspecialVigente` continua sendo o confirmado pelo Core. O fechamento após
      `EFETIVADA` pertence a #0005
- [ ] AC37 (parcial) — `FORA_DA_POLITICA_AUTOMATICA` renderiza a semântica exata e não afirma risco
      elevado, problema cadastral nem inelegibilidade permanente; `502`, `503` e `504` renderizam uma
      única mensagem de indisponibilidade com ação de repetir. A mensagem de indeterminação pertence a
      #0006
- [ ] AC36 (parcial) — os meters introduzidos aqui usam apenas labels de baixa cardinalidade e nenhum
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
