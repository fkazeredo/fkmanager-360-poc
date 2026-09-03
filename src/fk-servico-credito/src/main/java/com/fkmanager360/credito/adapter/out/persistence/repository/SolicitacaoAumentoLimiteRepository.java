package com.fkmanager360.credito.adapter.out.persistence.repository;

import com.fkmanager360.credito.adapter.out.persistence.entity.SolicitacaoAumentoLimiteEntity;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface SolicitacaoAumentoLimiteRepository extends JpaRepository<SolicitacaoAumentoLimiteEntity, UUID> {

    /**
     * {@code carregarParaDecisao} (plano #0003, Fase 2) sempre precisa do contexto congelado
     * junto -- {@code @EntityGraph} evita que o mapeamento LAZY default produza uma segunda
     * consulta separada (N+1) para {@code contexto_decisao_credito}.
     */
    @EntityGraph(attributePaths = "contexto")
    Optional<SolicitacaoAumentoLimiteEntity> findComContextoById(UUID id);

    /**
     * TX2 (plano #0003, Fase 3): atualiza status/{@code efetivacao_id} sem passar pelo ciclo de
     * carregar-a-entity-e-salvar -- a leitura que precede esta escrita e o
     * {@code SELECT ... FOR UPDATE NOWAIT} nativo (fora do escopo do Hibernate), entao um UPDATE
     * direto e mais fiel ao que realmente acontece do que fingir uma entity JPA gerenciada aqui.
     */
    @Modifying
    @Query("update SolicitacaoAumentoLimiteEntity s "
            + "set s.status = :status, s.efetivacaoId = :efetivacaoId, s.atualizadaEm = :atualizadaEm "
            + "where s.id = :id")
    void atualizarStatusEEfetivacao(
            @Param("id") UUID id,
            @Param("status") String status,
            @Param("efetivacaoId") UUID efetivacaoId,
            @Param("atualizadaEm") Instant atualizadaEm);
}
