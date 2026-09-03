package com.fkmanager360.credito.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * O header HTTP "Idempotency-Key" da submissao (spec, secao "Idempotencia da submissao"). O nome
 * fica em ingles deliberadamente -- a linguagem ubiqua desta plataforma adotou o termo verbatim,
 * porque e o nome do proprio header, e traduzir para algo como "ChaveIdempotencia" inventaria
 * vocabulario que a spec e o glossario nao usam.
 *
 * <p>Escopo de unicidade e {@code originadorId + key} (nao a key isolada): dois gerentes podem, em
 * tese, cunhar o mesmo UUID sem colisao de negocio.
 */
public record IdempotencyKey(UUID valor) {

    public IdempotencyKey {
        Objects.requireNonNull(valor, "IdempotencyKey nao pode ser nula");
    }
}
