package com.fkmanager360.credito.application.port.out;

import java.util.Objects;

/**
 * Resultado de uma reclamacao de claim (plano #0004, secao 1 -- loop de lote e claim unitario).
 * {@link EsgotadaAgora} e a terminalizacao tecnica de uma entrega cujas tentativas ja atingiram o
 * maximo permitido SEM que nenhum novo episodio HTTP seja iniciado -- cobre tanto o esgotamento
 * "normal" (respostas transitorias consumiram todas as tentativas) quanto o crash entre o commit
 * do claim e o envio HTTP da ultima tentativa reservada (secao 1, regra normativa do Owner):
 * nos dois casos a transicao {@code PENDENTE -> ESGOTADA} acontece sob o MESMO lock de
 * {@code FOR UPDATE SKIP LOCKED} que reclamaria a entrega, nunca por check-then-update
 * desprotegido.
 */
public sealed interface ReclamacaoEntrega {

    record NenhumaPendente() implements ReclamacaoEntrega {
    }

    record Reclamada(EntregaEfetivacaoReclamada entrega) implements ReclamacaoEntrega {
        public Reclamada {
            Objects.requireNonNull(entrega, "entrega e obrigatoria");
        }
    }

    record EsgotadaAgora() implements ReclamacaoEntrega {
    }
}
