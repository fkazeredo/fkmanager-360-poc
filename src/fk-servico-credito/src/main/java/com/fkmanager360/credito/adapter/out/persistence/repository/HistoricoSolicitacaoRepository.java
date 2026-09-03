package com.fkmanager360.credito.adapter.out.persistence.repository;

import com.fkmanager360.credito.adapter.out.persistence.entity.HistoricoSolicitacaoEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/** Escrita convencional via {@link JpaRepository}; ninguem le esta tabela neste ticket. */
public interface HistoricoSolicitacaoRepository extends JpaRepository<HistoricoSolicitacaoEntity, Long> {
}
