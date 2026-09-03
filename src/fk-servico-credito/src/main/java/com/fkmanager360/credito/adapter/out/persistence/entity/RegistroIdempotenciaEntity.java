package com.fkmanager360.credito.adapter.out.persistence.entity;

import com.fkmanager360.credito.application.port.out.NovaSolicitacaoAumentoLimite;
import com.fkmanager360.credito.application.port.out.RegistroIdempotencia;
import com.fkmanager360.credito.domain.AtorId;
import com.fkmanager360.credito.domain.IdempotencyKey;
import com.fkmanager360.credito.domain.SolicitacaoId;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Espelho JPA de {@code registro_idempotencia} (V1__criar_estado_duravel_de_credito.sql). Escrito
 * uma unica vez dentro de TX1 e nunca atualizado depois (mesma garantia documentada no adapter
 * antigo).
 *
 * <p>{@code fingerprint} e {@code CHAR(64)} no schema (SHA-256 hex de {@link
 * com.fkmanager360.credito.application.FingerprintCanonico}, sempre 64 caracteres).
 * <b>Verificado empiricamente</b>: {@code @Column(columnDefinition = "char(64)")} NAO satisfaz
 * {@code ddl-auto=validate} -- Hibernate interpreta o texto literal do {@code columnDefinition}
 * como se descrevesse um {@code VARCHAR} e compara pelo NOME do tipo, nao pelo codigo JDBC,
 * produzindo {@code SchemaManagementException: found [bpchar (Types#CHAR)], but expecting
 * [char(64) (Types#VARCHAR)]}. {@code @JdbcTypeCode(SqlTypes.CHAR)} resolve isso corretamente:
 * declara o tipo JDBC esperado explicitamente (compativel com {@code bpchar}, que o driver
 * PostgreSQL reporta como {@code Types.CHAR}), em vez de deixar Hibernate inferir {@code VARCHAR}
 * pelo tipo Java {@code String}.
 *
 * <p>Sem {@code equals}/{@code hashCode} nesta entity (a identidade composta ja vive em
 * {@link RegistroIdempotenciaId}, que a implementa).
 */
@Entity
@Table(name = "registro_idempotencia")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RegistroIdempotenciaEntity {

    @EmbeddedId
    private RegistroIdempotenciaId id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "fingerprint", nullable = false, length = 64)
    private String fingerprint;

    @Column(name = "solicitacao_id", nullable = false)
    private UUID solicitacaoId;

    @Column(name = "criado_em", nullable = false)
    private Instant criadoEm;

    public static RegistroIdempotenciaEntity de(NovaSolicitacaoAumentoLimite dados, UUID solicitacaoId) {
        RegistroIdempotenciaEntity entity = new RegistroIdempotenciaEntity();
        entity.id = new RegistroIdempotenciaId(dados.originadorId().valor(), dados.idempotencyKey().valor());
        entity.fingerprint = dados.fingerprint();
        entity.solicitacaoId = solicitacaoId;
        entity.criadoEm = dados.registradaEm();
        return entity;
    }

    public RegistroIdempotencia toDomain() {
        return new RegistroIdempotencia(
                new AtorId(id.getOriginadorId()),
                new IdempotencyKey(id.getIdempotencyKey()),
                fingerprint,
                new SolicitacaoId(solicitacaoId),
                criadoEm);
    }
}
