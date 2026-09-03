package com.fkmanager360.credito.application.port.out;

import com.fkmanager360.credito.domain.AtorOperacao;

import java.time.Instant;
import java.util.Objects;

/**
 * Uma entrada da trilha de historico funcional, append-only (spec, secao "Historico funcional").
 *
 * <p>{@code fatoId} precisa ser DETERMINISTICO a partir do fato causador -- por exemplo
 * {@code "DECISAO:" + solicitacaoId} para a decisao automatica -- para que a UNIQUE constraint em
 * {@code fato_id} (proxima etapa, persistencia) deduplique sob replay/redelivery sem produzir uma
 * segunda entrada para o mesmo fato logico. O valor em si e responsabilidade de quem constroi a
 * entrada (os casos de uso), nao deste tipo.
 */
public record EntradaHistorico(
        String fatoId,
        TipoFatoHistorico tipoFato,
        AtorOperacao autor,
        Instant ocorridoEm) {

    public EntradaHistorico {
        if (fatoId == null || fatoId.isBlank()) {
            throw new IllegalArgumentException("fatoId e obrigatorio");
        }
        Objects.requireNonNull(tipoFato, "tipoFato e obrigatorio");
        Objects.requireNonNull(autor, "autor e obrigatorio");
        Objects.requireNonNull(ocorridoEm, "ocorridoEm e obrigatorio");
    }
}
