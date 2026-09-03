package com.fkmanager360.credito.domain;

import java.util.Objects;

/**
 * A peca minima que encapsula a invariante de transicao da SolicitacaoAumentoLimite. Nao e um
 * agregado JPA nem carrega os campos de persistencia (identidade, cliente, conta, contexto,
 * historico) -- isso pertence a camada de aplicacao e ao adapter de persistencia. Este tipo existe
 * para que a regra "so estas transicoes sao validas, e um terminal nunca e reescrito" seja
 * testavel em JUnit puro, sem infraestrutura alguma (ADR-0018).
 */
public record SolicitacaoAumentoLimite(StatusSolicitacaoAumentoLimite status) {

    public SolicitacaoAumentoLimite {
        Objects.requireNonNull(status, "status e obrigatorio");
    }

    /**
     * O estado inicial de toda SolicitacaoAumentoLimite (spec: "criacao -&gt; SOLICITADA").
     */
    public static SolicitacaoAumentoLimite criar() {
        return new SolicitacaoAumentoLimite(StatusSolicitacaoAumentoLimite.SOLICITADA);
    }

    /**
     * Produz o proximo estado, validando a transicao. Nunca reescreve um terminal e nunca aceita
     * uma transicao fora da tabela da spec -- as duas coisas resultam em
     * {@link TransicaoInvalidaException}.
     */
    public SolicitacaoAumentoLimite transicionarPara(StatusSolicitacaoAumentoLimite alvo) {
        if (!status.podeTransicionarPara(alvo)) {
            throw new TransicaoInvalidaException(
                    "Transicao invalida de " + status + " para " + alvo);
        }
        return new SolicitacaoAumentoLimite(alvo);
    }
}
