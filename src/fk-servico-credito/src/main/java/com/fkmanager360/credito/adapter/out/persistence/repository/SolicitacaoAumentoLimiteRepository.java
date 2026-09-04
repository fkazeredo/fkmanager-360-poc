package com.fkmanager360.credito.adapter.out.persistence.repository;

import com.fkmanager360.credito.adapter.out.persistence.entity.SolicitacaoAumentoLimiteEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

    /**
     * Aceite da instrucao de efetivacao (plano #0004, secao 9): so escreve o {@code protocolo_core}
     * -- a divergencia com um valor ja existente e decidida ANTES desta chamada (leitura de
     * {@link #buscarProtocoloCore}), porque o {@code UPDATE} por si so nao tem como recusar
     * sobrescrever silenciosamente.
     */
    @Modifying
    @Query("update SolicitacaoAumentoLimiteEntity s "
            + "set s.protocoloCore = :protocoloCore, s.atualizadaEm = :atualizadaEm "
            + "where s.id = :id")
    void atualizarProtocoloCore(
            @Param("id") UUID id, @Param("protocoloCore") String protocoloCore, @Param("atualizadaEm") Instant atualizadaEm);

    @Query("select s.protocoloCore from SolicitacaoAumentoLimiteEntity s where s.id = :id")
    Optional<String> buscarProtocoloCore(@Param("id") UUID id);

    /**
     * Correlaciona por {@code EfetivacaoId} -- nunca por {@code ProtocoloCore}, que pode ser
     * desconhecido quando o aceite se perdeu (ADR-0009, emenda). {@code PESSIMISTIC_WRITE} serializa
     * concluir-com-resultado contra qualquer outro escritor concorrente da MESMA solicitacao
     * (callback e reconciliacao, em #0005/#0006, chamarao esta mesma consulta pela mesma porta).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SolicitacaoAumentoLimiteEntity s where s.efetivacaoId = :efetivacaoId")
    Optional<SolicitacaoAumentoLimiteEntity> buscarPorEfetivacaoIdParaAtualizar(@Param("efetivacaoId") UUID efetivacaoId);

    @Modifying
    @Query("update SolicitacaoAumentoLimiteEntity s "
            + "set s.status = :status, s.motivoFalhaEfetivacao = :motivo, s.atualizadaEm = :atualizadaEm "
            + "where s.id = :id")
    void atualizarStatusEMotivoFalha(
            @Param("id") UUID id,
            @Param("status") String status,
            @Param("motivo") String motivo,
            @Param("atualizadaEm") Instant atualizadaEm);

    /**
     * Conclusao por sucesso (#0005): sem motivo de falha a gravar -- espelha
     * {@link #atualizarStatusEMotivoFalha} sem a coluna que so faz sentido para
     * {@code FALHA_EFETIVACAO}.
     */
    @Modifying
    @Query("update SolicitacaoAumentoLimiteEntity s "
            + "set s.status = :status, s.atualizadaEm = :atualizadaEm "
            + "where s.id = :id")
    void atualizarStatus(@Param("id") UUID id, @Param("status") String status, @Param("atualizadaEm") Instant atualizadaEm);

    /**
     * O {@code LimiteSolicitado} congelado no {@code ContextoDecisaoCredito} (#0005): usado para
     * conferir coerencia do {@code limiteEfetivado} de um callback de sucesso (AC26) sem carregar
     * a entity inteira do contexto.
     */
    @Query("select s.contexto.limiteSolicitado from SolicitacaoAumentoLimiteEntity s where s.id = :id")
    long buscarLimiteSolicitadoCongelado(@Param("id") UUID id);
}
