package com.fkmanager360.credito.adapter.out.persistence;

import com.fkmanager360.credito.application.port.out.RegistroIdempotencia;
import com.fkmanager360.credito.application.port.out.RegistroIdempotenciaPort;
import com.fkmanager360.credito.domain.AtorId;
import com.fkmanager360.credito.domain.IdempotencyKey;
import com.fkmanager360.credito.domain.SolicitacaoId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Adapter de saida sobre o armazenamento privado de Credito (ADR-0014). Leitura pura, sem
 * transacao: {@code registro_idempotencia} e escrito uma unica vez dentro de TX1
 * ({@link PostgresSolicitacoesAumentoLimiteAdapter#registrar}) e nunca atualizado depois, entao
 * uma consulta simples em autocommit e suficiente aqui -- nao ha nada a coordenar.
 *
 * <p>Usada tanto no pre-check de idempotencia da Fase 0 (passo 5, antes de qualquer chamada
 * remota) quanto na reclassificacao apos um conflito de TX1 (guardrail de concorrencia documentado
 * em {@link com.fkmanager360.credito.application.port.out.SolicitacoesAumentoLimitePort}) -- este
 * segundo uso e via injecao direta desta porta em
 * {@link PostgresSolicitacoesAumentoLimiteAdapter}, evitando duplicar a query em duas classes.
 */
@Repository
public class PostgresRegistroIdempotenciaAdapter implements RegistroIdempotenciaPort {

    private final JdbcClient jdbcClient;

    public PostgresRegistroIdempotenciaAdapter(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Optional<RegistroIdempotencia> buscar(AtorId originadorId, IdempotencyKey key) {
        return jdbcClient.sql("""
                        select fingerprint, solicitacao_id, criado_em
                        from registro_idempotencia
                        where originador_id = :originadorId and idempotency_key = :key
                        """)
                .param("originadorId", originadorId.valor())
                .param("key", key.valor())
                .query((rs, rowNum) -> new RegistroIdempotencia(
                        originadorId,
                        key,
                        rs.getString("fingerprint"),
                        new SolicitacaoId(rs.getObject("solicitacao_id", UUID.class)),
                        rs.getTimestamp("criado_em").toInstant()))
                .optional();
    }
}
