package com.fkmanager360.credito.application.port.out;

import com.fkmanager360.credito.domain.ContaId;
import com.fkmanager360.credito.domain.CorrelationId;
import com.fkmanager360.credito.domain.EfetivacaoId;
import com.fkmanager360.credito.domain.LimiteChequeEspecialVigente;
import com.fkmanager360.credito.domain.LimiteSolicitado;

import java.util.Objects;
import java.util.UUID;

/**
 * A intencao durável de efetivacao gravada no Outbox dentro de TX2, quando a decisao e APROVADA
 * (ADR-0009, plano #0003 D2). Ninguem consome esta intencao neste ticket -- o dispatcher nasce em
 * #0004 -- por isso o tipo carrega apenas os campos funcionais minimos, sem abstracao de
 * transporte ou destino.
 *
 * <p>{@code efetivacaoId} e {@code messageId} sao identidades distintas e ambas estaveis por toda
 * a vida da operacao, inclusive atraves de reenvios futuros (ADR-0009 e sua emenda):
 * {@code efetivacaoId} identifica a operacao de negocio perante o CoreLegado;
 * {@code messageId} identifica a mensagem logica no Outbox.
 * {@code limiteChequeEspecialVigenteEsperado} e o vigente CONGELADO no ContextoDecisaoCredito no
 * momento da decisao -- a precondicao que o CoreLegado usa para recusar aplicar por cima de um
 * estado que ja mudou.
 */
public record IntencaoEfetivacao(
        EfetivacaoId efetivacaoId,
        UUID messageId,
        ContaId contaId,
        LimiteChequeEspecialVigente limiteChequeEspecialVigenteEsperado,
        LimiteSolicitado limiteSolicitado,
        CorrelationId correlationId) {

    public IntencaoEfetivacao {
        Objects.requireNonNull(efetivacaoId, "efetivacaoId e obrigatorio");
        Objects.requireNonNull(messageId, "messageId e obrigatorio");
        Objects.requireNonNull(contaId, "contaId e obrigatorio");
        Objects.requireNonNull(limiteChequeEspecialVigenteEsperado, "limiteChequeEspecialVigenteEsperado e obrigatorio");
        Objects.requireNonNull(limiteSolicitado, "limiteSolicitado e obrigatorio");
        Objects.requireNonNull(correlationId, "correlationId e obrigatorio");
    }
}
