---
id: 0005
title: O CoreLegado confirma e a solicitação termina EFETIVADA
state: open
triage: ready-for-agent
spec: docs/specs/slice-1-straight-through-approval.md
blocked_by: [0004]
assignee:
created: 2026-09-02
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

- [ ] AC1 — a jornada completa: conta regular, valores na faixa automática, `APROVADA` com
      `versaoPoliticaCredito = v1`, `AGUARDANDO_EFETIVACAO`, uma única intenção durável, e após a
      confirmação autoritativa `EFETIVADA`, com a consulta posterior ao Core apresentando o novo
      vigente e o histórico contendo cada fato uma única vez
- [ ] AC13 — callback duplicado idêntico devolve `200`, o estado fica inalterado, o número de entradas
      funcionais de histórico não muda e nenhum efeito é repetido
- [ ] AC14 — callback que chega antes do registro do aceite localiza a operação por `EfetivacaoId`,
      conclui, aprende o `ProtocoloCore`, e o processamento posterior da resposta de aceite não regride
      o estado; protocolo igual é no-op idempotente
- [ ] AC17 — callback contraditório sobre estado terminal devolve `2xx`, não reescreve o estado, não
      inventa transição no histórico, não inicia nova efetivação e registra anomalia observável
- [ ] AC26 — callback de sucesso com `limiteEfetivado` incompatível não transiciona para `EFETIVADA`
      nem sobrescreve o resultado esperado; registra anomalia; a operação permanece recuperável, e um
      resultado autoritativo coerente posterior ainda a conclui
- [ ] AC29 — o valor solicitado só passa a figurar como vigente depois de `EFETIVADA` e de leitura
      autoritativa do Core
- [ ] AC36 (parcial) — os meters de resultado de efetivação e de anomalia de callback respeitam a
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
