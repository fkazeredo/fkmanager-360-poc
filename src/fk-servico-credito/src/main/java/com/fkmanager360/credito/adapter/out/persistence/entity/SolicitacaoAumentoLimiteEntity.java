package com.fkmanager360.credito.adapter.out.persistence.entity;

import com.fkmanager360.credito.application.port.out.CargaParaDecisao;
import com.fkmanager360.credito.application.port.out.NovaSolicitacaoAumentoLimite;
import com.fkmanager360.credito.domain.ContaId;
import com.fkmanager360.credito.domain.CorrelationId;
import com.fkmanager360.credito.domain.StatusSolicitacaoAumentoLimite;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Espelho JPA de {@code solicitacao_aumento_limite} (V1__criar_estado_duravel_de_credito.sql). E
 * adapter, nao dominio (ADR-0020): {@link com.fkmanager360.credito.domain.SolicitacaoAumentoLimite}
 * -- o agregado de verdade -- vive em {@code domain}, sem anotacao alguma.
 *
 * <p>{@code id} e atribuido pela aplicacao (UUID aleatorio, gerado no adapter de persistencia
 * antes de TX1), nao pelo banco -- por isso nao ha {@code @GeneratedValue} aqui.
 *
 * <p>Sem {@code equals}/{@code hashCode}: nenhuma colecao deste modulo agrupa ou deduplica
 * instancias desta entity por identidade -- cada leitura e traduzida para os records de dominio
 * ({@link CargaParaDecisao}, etc.) antes de qualquer estrutura de dados fazer uso dela.
 */
@Entity
@Table(name = "solicitacao_aumento_limite")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SolicitacaoAumentoLimiteEntity {

    @Id
    private UUID id;

    @Column(name = "cliente_id", nullable = false)
    private String clienteId;

    @Column(name = "conta_id", nullable = false)
    private String contaId;

    @Column(name = "originador_id", nullable = false)
    private String originadorId;

    @Column(name = "origem_solicitacao", nullable = false)
    private String origemSolicitacao;

    @Column(name = "canal_manifestacao", nullable = false)
    private String canalManifestacao;

    @Column(name = "observacao")
    private String observacao;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "correlation_id", nullable = false)
    private UUID correlationId;

    @Column(name = "efetivacao_id")
    private UUID efetivacaoId;

    // #0004: preenchido no aceite (ACEITE); nunca sobrescrito por um valor divergente para o
    // mesmo EfetivacaoId (anomalia observavel, nao erro de negocio -- plano #0004, secao 9).
    @Column(name = "protocolo_core")
    private String protocoloCore;

    // #0004: preenchido somente quando status = FALHA_EFETIVACAO por retorno definitivo do Core
    // ja na propria instrucao (AC15).
    @Column(name = "motivo_falha_efetivacao")
    private String motivoFalhaEfetivacao;

    @Column(name = "registrada_em", nullable = false)
    private Instant registradaEm;

    @Column(name = "atualizada_em", nullable = false)
    private Instant atualizadaEm;

    // Lado inverso (nao proprietario) do @OneToOne @MapsId de ContextoDecisaoCreditoEntity.
    // LAZY por padrao (ticket): carregarParaDecisao usa @EntityGraph explicito para evitar N+1.
    @OneToOne(mappedBy = "solicitacao", fetch = FetchType.LAZY)
    private ContextoDecisaoCreditoEntity contexto;

    public static SolicitacaoAumentoLimiteEntity de(NovaSolicitacaoAumentoLimite dados, UUID id) {
        SolicitacaoAumentoLimiteEntity entity = new SolicitacaoAumentoLimiteEntity();
        entity.id = id;
        entity.clienteId = dados.clienteId().valor();
        entity.contaId = dados.contaId().valor();
        entity.originadorId = dados.originadorId().valor();
        entity.origemSolicitacao = dados.origemSolicitacao().name();
        entity.canalManifestacao = dados.manifestacaoCliente().canalManifestacao().name();
        entity.observacao = dados.manifestacaoCliente().observacao();
        entity.status = StatusSolicitacaoAumentoLimite.SOLICITADA.name();
        entity.correlationId = dados.correlationId().valor();
        entity.efetivacaoId = null;
        entity.registradaEm = dados.registradaEm();
        entity.atualizadaEm = dados.registradaEm();
        return entity;
    }

    /**
     * Requer {@code contexto} carregado (ver {@code @EntityGraph} no repository) -- a fotografia
     * imutavel de {@link com.fkmanager360.credito.domain.ContextoDecisaoCredito} e parte
     * obrigatoria de {@link CargaParaDecisao} (plano #0003, Fase 2).
     */
    public CargaParaDecisao toCargaParaDecisao() {
        return new CargaParaDecisao(
                StatusSolicitacaoAumentoLimite.valueOf(status),
                contexto.toDomain(),
                new ContaId(contaId),
                new CorrelationId(correlationId));
    }
}
