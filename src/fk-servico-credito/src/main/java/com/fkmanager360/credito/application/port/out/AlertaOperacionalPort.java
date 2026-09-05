package com.fkmanager360.credito.application.port.out;

import com.fkmanager360.credito.domain.EfetivacaoId;
import com.fkmanager360.credito.domain.SolicitacaoId;

import java.time.Instant;

/**
 * Sinal de alerta operacional (#0006, AC35). Disparado exclusivamente quando
 * {@link ResultadoIndeterminacao.IndeterminadaAgora} e observado -- nunca por uma tentativa, nunca
 * por reentrada sobre uma janela ja indeterminada ({@link ResultadoIndeterminacao.JaEstavaIndeterminada}).
 * O estado funcional (a transicao persistida) sempre precede a chamada a esta porta: nenhuma
 * tentativa e feita para tornar a escrita em PostgreSQL e este sinal atomicos entre si.
 *
 * <p>{@code EFETIVACAO_INDETERMINADA} e ignorancia sobre o resultado, nunca falha de efetivacao
 * (ADR-0009, emenda) -- nenhuma implementacao desta porta pode afirmar o contrario no texto do
 * alerta.
 */
public interface AlertaOperacionalPort {

    void efetivacaoIndeterminada(EfetivacaoId efetivacaoId, SolicitacaoId solicitacaoId, Instant ocorridoEm);
}
