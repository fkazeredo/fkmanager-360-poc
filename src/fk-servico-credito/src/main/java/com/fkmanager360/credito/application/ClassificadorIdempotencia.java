package com.fkmanager360.credito.application;

import com.fkmanager360.credito.application.port.out.RegistroIdempotencia;

import java.util.Objects;

/**
 * Classifica um {@link RegistroIdempotencia} encontrado, comparando o fingerprint desta requisicao
 * com o fingerprint gravado -- seja o registro tendo sido encontrado pelo pre-check da Fase 0
 * (passo 5, antes de qualquer chamada remota), seja pela releitura apos um conflito de TX1
 * (guardrail de concorrencia documentado em
 * {@code SolicitacoesAumentoLimitePort#registrar}). E FUNCAO PURA e reaproveitavel: os dois
 * pontos de entrada de {@code RegistrarSolicitacaoAumentoLimite} que podem encontrar um registro
 * de idempotencia chamam esta MESMA classificacao, para que a resposta ao gerente nunca dependa de
 * qual dos dois caminhos encontrou o registro (plano #0003, "Classificacao apos rollback -- a
 * idempotencia tem precedencia").
 *
 * <p>Esta classe decide apenas "o fingerprint bate ou nao". Quando bate, o comando e uma repeticao
 * legitima da mesma tentativa logica -- e cabe ao caso de uso decidir se isso significa reler uma
 * decisao ja concluida (replay) ou retomar a Fase 2/3 sobre uma solicitacao ainda {@code
 * SOLICITADA}, o que depende do estado persistido e por isso nao e responsabilidade desta funcao
 * pura. Quando nao bate, a mesma chave foi reutilizada com um payload diferente -- um caso que a
 * spec recusa sempre, independentemente do estado da solicitacao referenciada.
 */
public final class ClassificadorIdempotencia {

    private ClassificadorIdempotencia() {
    }

    public enum Classificacao {
        /** Fingerprint coincide: repeticao legitima -- replay ou retomada, decidido por quem chama. */
        REPLAY_OU_RETOMADA,
        /** Mesma Idempotency-Key, payload diferente -- sempre recusado (422). */
        FINGERPRINT_DIVERGENTE
    }

    public static Classificacao classificar(RegistroIdempotencia registro, String fingerprintCalculado) {
        Objects.requireNonNull(registro, "registro e obrigatorio");
        Objects.requireNonNull(fingerprintCalculado, "fingerprintCalculado e obrigatorio");

        return registro.fingerprint().equals(fingerprintCalculado)
                ? Classificacao.REPLAY_OU_RETOMADA
                : Classificacao.FINGERPRINT_DIVERGENTE;
    }
}
