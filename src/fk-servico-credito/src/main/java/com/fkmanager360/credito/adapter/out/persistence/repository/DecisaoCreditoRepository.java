package com.fkmanager360.credito.adapter.out.persistence.repository;

import com.fkmanager360.credito.adapter.out.persistence.entity.DecisaoCreditoEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** {@code findById(solicitacaoId)}, herdado de {@link JpaRepository}, cobre o unico uso atual (TX2, replay). */
public interface DecisaoCreditoRepository extends JpaRepository<DecisaoCreditoEntity, UUID> {
}
