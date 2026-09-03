package com.fkmanager360.credito.adapter.out.persistence.entity;

import com.fkmanager360.credito.domain.AtorHumano;
import com.fkmanager360.credito.domain.AtorId;
import com.fkmanager360.credito.domain.AtorOperacao;
import com.fkmanager360.credito.domain.AtorSistema;

/**
 * Traducao entre {@link AtorOperacao} (sealed: {@link AtorHumano} | {@link AtorSistema}) e as duas
 * colunas achatadas {@code ator_tipo}/{@code ator_id} -- reaproveitada por
 * {@link DecisaoCreditoEntity} e {@link HistoricoSolicitacaoEntity}, as duas tabelas que carregam
 * autoria. Mesma logica (switch pattern-matching) que vivia como metodo privado em
 * {@code PostgresSolicitacoesAumentoLimiteAdapter} antes deste refactor para JPA; extraida aqui
 * apenas porque agora dois tipos diferentes precisam dela, nao por antecipacao de reuso futuro
 * (ADR-0020).
 */
record AtorColunas(String tipo, String id) {

    static AtorColunas de(AtorOperacao ator) {
        return switch (ator) {
            case AtorHumano h -> new AtorColunas("HUMANO", h.id().valor());
            case AtorSistema s -> new AtorColunas("SISTEMA", s.nome());
        };
    }

    static AtorOperacao paraAtorOperacao(String tipo, String id) {
        return switch (tipo) {
            case "HUMANO" -> new AtorHumano(new AtorId(id));
            case "SISTEMA" -> new AtorSistema(id);
            default -> throw new IllegalStateException("ator_tipo desconhecido: " + tipo);
        };
    }
}
