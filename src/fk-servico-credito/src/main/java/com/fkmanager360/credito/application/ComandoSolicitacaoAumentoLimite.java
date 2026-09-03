package com.fkmanager360.credito.application;

import com.fkmanager360.credito.domain.AtorId;
import com.fkmanager360.credito.domain.ClienteId;
import com.fkmanager360.credito.domain.ContaId;
import com.fkmanager360.credito.domain.IdempotencyKey;

import java.util.Objects;

/**
 * Comando ja normalizado (trim, canonicalizacao de ids) recebido da borda web -- o parsing
 * estrutural do corpo (passos 1-3 da Fase 0 do plano #0003: header presente e UUID, corpo
 * interpretavel como JSON, campos triviaimente normalizados) e responsabilidade da proxima etapa
 * (adapter/in/web), nao deste tipo nem do caso de uso que o recebe.
 *
 * <p>{@code limiteSolicitado} e {@code limiteVigenteVisto} permanecem {@link Long} boxed
 * deliberadamente: podem ser {@code null} (campo ausente no corpo), negativos ou zero -- a
 * validacao pura local (passo 6 da Fase 0) e responsabilidade do caso de uso
 * {@code RegistrarSolicitacaoAumentoLimite}, nao do binding HTTP, porque a taxonomia da spec exige
 * que "ausente" e "&lt;= 0" sejam {@code 422} (comando invalido), e nao {@code 400} (estrutura).
 * Pela mesma razao, {@code canalManifestacao} chega como {@link String} bruta, nao como o enum
 * {@code CanalManifestacao} -- um valor fora do enum e erro semantico, nao estrutural.
 */
public record ComandoSolicitacaoAumentoLimite(
        ClienteId clienteId,
        ContaId contaId,
        Long limiteSolicitado,
        Long limiteVigenteVisto,
        String canalManifestacao,
        String observacao,
        AtorId originadorId,
        IdempotencyKey idempotencyKey) {

    public ComandoSolicitacaoAumentoLimite {
        Objects.requireNonNull(clienteId, "clienteId e obrigatorio");
        Objects.requireNonNull(contaId, "contaId e obrigatorio");
        Objects.requireNonNull(originadorId, "originadorId e obrigatorio");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey e obrigatoria");
    }
}
