package com.fkmanager360.credito.application.port.out;

import com.fkmanager360.credito.domain.DecisaoCredito;
import com.fkmanager360.credito.domain.StatusSolicitacaoAumentoLimite;

import java.util.Objects;

/**
 * Resultado de TX2 ({@link SolicitacoesAumentoLimitePort#aplicarDecisao}).
 *
 * <p>{@code decidiuAgora=true} significa que a solicitacao estava {@code SOLICITADA} quando o
 * adapter tentou aplicar, e a decisao recem-calculada foi de fato persistida agora --
 * {@code decisaoVigente} e a decisao que acabou de ser gravada.
 *
 * <p>{@code decidiuAgora=false} significa que a solicitacao ja nao estava {@code SOLICITADA}
 * (replay, retomada concorrente ja concluida por outra chamada, ou qualquer estado posterior) --
 * nada foi reescrito, e {@code statusResultante}/{@code decisaoVigente} devolvem exatamente o que
 * ja estava persistido. E este campo, e nao uma inspecao externa, que sustenta "replay nao
 * recalcula decisao": o caso de uso sempre chama {@code aplicarDecisao}, e e o adapter quem decide,
 * atomicamente, se ha algo novo a escrever.
 */
public record ResultadoAplicacaoDecisao(
        boolean decidiuAgora,
        StatusSolicitacaoAumentoLimite statusResultante,
        DecisaoCredito decisaoVigente) {

    public ResultadoAplicacaoDecisao {
        Objects.requireNonNull(statusResultante, "statusResultante e obrigatorio");
        Objects.requireNonNull(decisaoVigente, "decisaoVigente e obrigatoria");
    }
}
