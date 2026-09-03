package com.fkmanager360.credito.adapter.out.persistence.repository;

import com.fkmanager360.credito.adapter.out.persistence.entity.OutboxMensagemEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** Escrita convencional via {@link JpaRepository}; ninguem le esta tabela neste ticket (o dispatcher nasce em #0004). */
public interface OutboxMensagemRepository extends JpaRepository<OutboxMensagemEntity, UUID> {
}
