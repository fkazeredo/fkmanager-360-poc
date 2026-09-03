package com.fkmanager360.credito.domain;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Estado operacional do processo, distinto do resultado da DecisaoCredito (CONTEXT.md de Credito).
 * Existem apenas os seis estados que a spec do slice 1 exercita (ADR-0010 e sua emenda de
 * 2026-09-02): os tres primeiros nao terminais, os tres ultimos terminais.
 *
 * <p>A tabela de transicoes abaixo e <b>completa</b> -- inclui as transicoes que nenhum caso de
 * uso de #0003 alcanca (por exemplo {@code AGUARDANDO_EFETIVACAO -> EFETIVADA}), porque a maquina
 * de estados e regra de dominio testada exaustivamente em S1 independentemente de qual slice ja
 * a invoca (ADR-0018).
 */
public enum StatusSolicitacaoAumentoLimite {
    SOLICITADA,
    AGUARDANDO_EFETIVACAO,
    EFETIVACAO_INDETERMINADA,
    EFETIVADA,
    REJEITADA,
    FALHA_EFETIVACAO;

    private static final Map<StatusSolicitacaoAumentoLimite, Set<StatusSolicitacaoAumentoLimite>> TRANSICOES_VALIDAS =
            construirTransicoesValidas();

    private static Map<StatusSolicitacaoAumentoLimite, Set<StatusSolicitacaoAumentoLimite>> construirTransicoesValidas() {
        Map<StatusSolicitacaoAumentoLimite, Set<StatusSolicitacaoAumentoLimite>> transicoes =
                new EnumMap<>(StatusSolicitacaoAumentoLimite.class);
        transicoes.put(SOLICITADA, Set.of(REJEITADA, AGUARDANDO_EFETIVACAO));
        transicoes.put(AGUARDANDO_EFETIVACAO, Set.of(EFETIVADA, FALHA_EFETIVACAO, EFETIVACAO_INDETERMINADA));
        transicoes.put(EFETIVACAO_INDETERMINADA, Set.of(EFETIVADA, FALHA_EFETIVACAO));
        transicoes.put(EFETIVADA, Set.of());
        transicoes.put(REJEITADA, Set.of());
        transicoes.put(FALHA_EFETIVACAO, Set.of());
        return Map.copyOf(transicoes);
    }

    /**
     * Os tres estados terminais nunca sao reescritos -- decidir novamente uma solicitacao
     * terminal e transicao invalida (spec, secao "Maquina de estados"). E tambem o conjunto que
     * define, por exclusao, os estados "nao terminais" que a unicidade por ContaCorrente usa
     * (spec, secao "Unicidade nao terminal por ContaCorrente").
     */
    public boolean isTerminal() {
        return TRANSICOES_VALIDAS.get(this).isEmpty();
    }

    public boolean podeTransicionarPara(StatusSolicitacaoAumentoLimite alvo) {
        Objects.requireNonNull(alvo, "alvo e obrigatorio");
        return TRANSICOES_VALIDAS.get(this).contains(alvo);
    }
}
