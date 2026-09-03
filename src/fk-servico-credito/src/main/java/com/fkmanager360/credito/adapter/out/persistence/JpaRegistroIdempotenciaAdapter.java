package com.fkmanager360.credito.adapter.out.persistence;

import com.fkmanager360.credito.adapter.out.persistence.entity.RegistroIdempotenciaEntity;
import com.fkmanager360.credito.adapter.out.persistence.entity.RegistroIdempotenciaId;
import com.fkmanager360.credito.adapter.out.persistence.repository.RegistroIdempotenciaRepository;
import com.fkmanager360.credito.application.port.out.RegistroIdempotencia;
import com.fkmanager360.credito.application.port.out.RegistroIdempotenciaPort;
import com.fkmanager360.credito.domain.AtorId;
import com.fkmanager360.credito.domain.IdempotencyKey;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Adapter de saida sobre o armazenamento privado de Credito (ADR-0014). Leitura pura, sem
 * transacao propria: {@code registro_idempotencia} e escrito uma unica vez dentro de TX1
 * ({@link CreditoPersistenceOperations#registrarTx1}) e nunca atualizado depois, entao uma
 * consulta simples e suficiente aqui -- nao ha nada a coordenar.
 *
 * <p>Usada tanto no pre-check de idempotencia da Fase 0 (passo 5, antes de qualquer chamada
 * remota) quanto na reclassificacao apos um conflito de TX1 (guardrail de concorrencia documentado
 * em {@link com.fkmanager360.credito.application.port.out.SolicitacoesAumentoLimitePort}) -- este
 * segundo uso e via injecao direta desta porta em
 * {@link JpaSolicitacoesAumentoLimiteAdapter}, evitando duplicar a consulta em duas classes.
 */
@Repository
@RequiredArgsConstructor
public class JpaRegistroIdempotenciaAdapter implements RegistroIdempotenciaPort {

    private final RegistroIdempotenciaRepository repository;

    @Override
    public Optional<RegistroIdempotencia> buscar(AtorId originadorId, IdempotencyKey key) {
        return repository.findById(new RegistroIdempotenciaId(originadorId.valor(), key.valor()))
                .map(RegistroIdempotenciaEntity::toDomain);
    }
}
