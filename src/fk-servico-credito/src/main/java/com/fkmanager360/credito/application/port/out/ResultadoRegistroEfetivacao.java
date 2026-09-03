package com.fkmanager360.credito.application.port.out;

import com.fkmanager360.credito.domain.StatusSolicitacaoAumentoLimite;

import java.time.Duration;
import java.util.Objects;

/**
 * Resultado de {@link ResultadoEfetivacaoPort#registrar} -- mesma forma de
 * {@code ResultadoAplicacaoDecisao} (TX2, #0003): {@code concluiuAgora=false} quando a solicitacao
 * ja estava em estado terminal (idempotencia sob redelivery/concorrencia -- callback duplicado,
 * reconciliacao apos o dispatcher ja ter concluido, etc.), e {@code true} quando esta chamada
 * efetivamente aplicou a transicao.
 *
 * <p>{@code permanenciaEmAguardandoEfetivacao} e o tempo entre a decisao aprovada (TX2, #0003) e
 * esta conclusao -- {@code null} quando {@code concluiuAgora=false}, porque nao ha nova permanencia
 * a medir num no-op idempotente. Meter de baixa cardinalidade (AC36): nenhum identificador de
 * negocio o acompanha.
 */
public record ResultadoRegistroEfetivacao(
        boolean concluiuAgora, StatusSolicitacaoAumentoLimite statusResultante, Duration permanenciaEmAguardandoEfetivacao) {

    public ResultadoRegistroEfetivacao {
        Objects.requireNonNull(statusResultante, "statusResultante e obrigatorio");
        if (concluiuAgora && permanenciaEmAguardandoEfetivacao == null) {
            throw new IllegalArgumentException("permanenciaEmAguardandoEfetivacao e obrigatoria quando concluiuAgora=true");
        }
    }
}
