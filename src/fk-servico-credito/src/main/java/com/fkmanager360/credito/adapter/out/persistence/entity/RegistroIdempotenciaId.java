package com.fkmanager360.credito.adapter.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Chave composta de {@code registro_idempotencia}: {@code (originador_id, idempotency_key)} --
 * ver comentario da tabela na migration ("escopo de unicidade originadorId + Idempotency-Key, nao
 * a key isolada"). {@code @EmbeddedId} e o mapeamento JPA canonico para PK composta.
 *
 * <p><b>{@code equals}/{@code hashCode} implementados a mao (nao {@code @EqualsAndHashCode} do
 * Lombok automatico)</b>: esta e exatamente a excecao prevista -- identidade estavel com
 * necessidade real, porque o Hibernate usa estes dois metodos para localizar a entity no
 * persistence context e no cache de primeiro nivel a partir de um {@code @EmbeddedId}. Sem eles,
 * a identidade cairia na igualdade de referencia padrao de {@code Object}, e duas instancias
 * logicamente iguais (por exemplo, uma construida pela aplicacao e outra lida do banco) nunca
 * seriam reconhecidas como a mesma chave.
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RegistroIdempotenciaId implements Serializable {

    @Column(name = "originador_id", nullable = false)
    private String originadorId;

    @Column(name = "idempotency_key", nullable = false)
    private UUID idempotencyKey;

    public RegistroIdempotenciaId(String originadorId, UUID idempotencyKey) {
        this.originadorId = originadorId;
        this.idempotencyKey = idempotencyKey;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RegistroIdempotenciaId that)) {
            return false;
        }
        return Objects.equals(originadorId, that.originadorId) && Objects.equals(idempotencyKey, that.idempotencyKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(originadorId, idempotencyKey);
    }
}
