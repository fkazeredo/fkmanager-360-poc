package com.fkmanager360.credito.application.port.out;

import com.fkmanager360.credito.domain.MotivoFalhaEfetivacao;

import java.util.Objects;

/**
 * O resultado autoritativo de uma efetivacao, recebido por qualquer um dos caminhos que convergem
 * em {@code RegistrarResultadoEfetivacao} (ADR-0009): a propria instrucao (#0004, quando o aceite
 * ja traz recusa definitiva), o callback (#0005) ou a reconciliacao (#0006). #0004 so produz
 * {@link FalhaDefinitiva} -- a variante de sucesso, com {@code limiteEfetivado}, nasce em #0005
 * junto do callback, que e quem primeiro precisa dela.
 */
public sealed interface ResultadoEfetivacaoRecebido {

    record FalhaDefinitiva(MotivoFalhaEfetivacao motivo) implements ResultadoEfetivacaoRecebido {
        public FalhaDefinitiva {
            Objects.requireNonNull(motivo, "motivo e obrigatorio");
        }
    }
}
