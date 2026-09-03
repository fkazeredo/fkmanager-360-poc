package com.fkmanager360.carteiraclientes.adapter.out.persistence.repository;

import com.fkmanager360.carteiraclientes.adapter.out.persistence.entity.VinculoCarteiraEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Acesso JPA a {@code vinculo_carteira}. Query derivation pura -- nenhum {@code @Query} foi
 * necessario para reproduzir o comportamento do antigo {@code PostgresVinculosCarteiraAdapter}.
 */
public interface VinculoCarteiraRepository extends JpaRepository<VinculoCarteiraEntity, Long> {

    /**
     * Equivalente ao antigo {@code SELECT EXISTS(...)} manual: o plano do PostgreSQL para na
     * primeira linha encontrada, e a unique constraint {@code uk_vinculo_carteira_gerente_cliente}
     * ja garante que nunca ha mais de uma linha para o par (gerenteId, clienteId).
     */
    boolean existsByGerenteIdAndClienteId(String gerenteId, String clienteId);

    /**
     * Equivalente ao antigo {@code SELECT ... ORDER BY id LIMIT :limite OFFSET :deslocamento}: a
     * ordenacao por {@code id} e a ordem de insercao (estavel), a mesma de antes.
     */
    Page<VinculoCarteiraEntity> findByGerenteIdOrderByIdAsc(String gerenteId, Pageable pageable);
}
