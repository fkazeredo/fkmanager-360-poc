package com.fkmanager360.credito.application.port.out;

import com.fkmanager360.credito.domain.AtorId;
import com.fkmanager360.credito.domain.IdempotencyKey;

import java.util.Optional;

/**
 * Leitura pura do registro de idempotencia -- usada no pre-check da Fase 0 (passo 5 do plano
 * #0003, ANTES de qualquer chamada remota) e reaproveitada apos um conflito de TX1 (ver o
 * guardrail de concorrencia documentado em {@link SolicitacoesAumentoLimitePort}). Os dois
 * caminhos classificam o resultado com a MESMA funcao
 * ({@code com.fkmanager360.credito.application.ClassificadorIdempotencia}).
 */
public interface RegistroIdempotenciaPort {

    Optional<RegistroIdempotencia> buscar(AtorId originadorId, IdempotencyKey key);
}
