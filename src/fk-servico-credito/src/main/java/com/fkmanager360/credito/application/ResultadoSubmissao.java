package com.fkmanager360.credito.application;

import com.fkmanager360.credito.application.port.out.ResultadoAplicacaoDecisao;
import com.fkmanager360.credito.domain.ContaId;
import com.fkmanager360.credito.domain.DecisaoCredito;
import com.fkmanager360.credito.domain.LimiteChequeEspecialVigente;
import com.fkmanager360.credito.domain.LimiteSolicitado;
import com.fkmanager360.credito.domain.SolicitacaoId;
import com.fkmanager360.credito.domain.StatusSolicitacaoAumentoLimite;

import java.time.Instant;
import java.util.Objects;

/**
 * Saida do fluxo submissao + decisao automatica na mesma requisicao (spec, User Story 33): tudo
 * que a borda web (proxima etapa) precisa para montar a resposta HTTP, incluindo
 * {@code criacaoNova}, que decide {@code 201} (criacao) vs {@code 200} (replay ou retomada).
 *
 * <p>Devolvido tanto por {@code RegistrarSolicitacaoAumentoLimite} quanto por
 * {@code DecidirSolicitacaoAumentoLimite}: o segundo sempre devolve {@code criacaoNova=false} --
 * decidir nunca e, por si so, uma criacao -- e o primeiro reconstroi o resultado com
 * {@code criacaoNova=true} apenas quando a chamada partiu de um registro genuinamente novo em TX1.
 *
 * <p>{@code decidiuAgora} e um sinal DISTINTO de {@code criacaoNova} (plano #0003, secao 10,
 * IMPORTANT 6): propagado sem alteracao de {@link ResultadoAplicacaoDecisao#decidiuAgora()} (TX2),
 * diz se uma DECISAO nova foi de fato calculada e persistida nesta chamada -- verdadeiro tanto na
 * criacao quanto na retomada de uma solicitacao {@code SOLICITADA} interrompida, e falso num
 * replay puro (solicitacao ja decidida antes). E este campo, e nao {@code criacaoNova}, que a
 * borda web usa para decidir se incrementa {@code decisoes_credito_total}: a metrica conta
 * decisoes, nao respostas HTTP.
 */
public record ResultadoSubmissao(
        SolicitacaoId solicitacaoId,
        ContaId contaId,
        StatusSolicitacaoAumentoLimite status,
        LimiteChequeEspecialVigente limiteChequeEspecialVigente,
        LimiteSolicitado limiteSolicitado,
        DecisaoCredito decisao,
        Instant registradaEm,
        boolean criacaoNova,
        boolean decidiuAgora) {

    public ResultadoSubmissao {
        Objects.requireNonNull(solicitacaoId, "solicitacaoId e obrigatorio");
        Objects.requireNonNull(contaId, "contaId e obrigatorio");
        Objects.requireNonNull(status, "status e obrigatorio");
        Objects.requireNonNull(limiteChequeEspecialVigente, "limiteChequeEspecialVigente e obrigatorio");
        Objects.requireNonNull(limiteSolicitado, "limiteSolicitado e obrigatorio");
        Objects.requireNonNull(decisao, "decisao e obrigatoria");
        Objects.requireNonNull(registradaEm, "registradaEm e obrigatorio");
    }
}
