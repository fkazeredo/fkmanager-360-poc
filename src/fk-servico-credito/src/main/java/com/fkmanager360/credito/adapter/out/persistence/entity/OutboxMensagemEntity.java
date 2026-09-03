package com.fkmanager360.credito.adapter.out.persistence.entity;

import com.fkmanager360.credito.application.port.out.IntencaoEfetivacao;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Espelho JPA de {@code outbox_mensagem} (V1__criar_estado_duravel_de_credito.sql): a intencao
 * duravel de {@code EfetivacaoLimite} (ADR-0009), gravada em TX2 quando a decisao e APROVADA.
 * Ninguem consome esta tabela neste ticket -- o dispatcher nasce em #0004 -- por isso nao ha
 * relacao JPA navegavel de volta para {@code SolicitacaoAumentoLimiteEntity}: {@code solicitacao_id}
 * fica como coluna simples, sem {@code @ManyToOne}/{@code @OneToOne}, porque nenhum caso de uso
 * atual precisa navegar esse grafo a partir daqui (ADR-0020 -- sem abstracao para o que nao e
 * usado).
 *
 * <p>Sem {@code equals}/{@code hashCode}: nenhuma colecao deste modulo agrupa ou deduplica
 * instancias desta entity por identidade.
 */
@Entity
@Table(name = "outbox_mensagem")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxMensagemEntity {

    @Id
    @Column(name = "message_id")
    private UUID messageId;

    @Column(name = "tipo", nullable = false)
    private String tipo;

    @Column(name = "destino", nullable = false)
    private String destino;

    @Column(name = "solicitacao_id", nullable = false)
    private UUID solicitacaoId;

    @Column(name = "efetivacao_id", nullable = false)
    private UUID efetivacaoId;

    @Column(name = "conta_id", nullable = false)
    private String contaId;

    @Column(name = "limite_cheque_especial_vigente_esperado", nullable = false)
    private long limiteChequeEspecialVigenteEsperado;

    @Column(name = "limite_solicitado", nullable = false)
    private long limiteSolicitado;

    @Column(name = "correlation_id", nullable = false)
    private UUID correlationId;

    @Column(name = "criado_em", nullable = false)
    private Instant criadoEm;

    public static OutboxMensagemEntity de(UUID solicitacaoId, IntencaoEfetivacao intencao, Instant criadoEm) {
        OutboxMensagemEntity entity = new OutboxMensagemEntity();
        entity.messageId = intencao.messageId();
        entity.tipo = "EfetivarLimite";
        entity.destino = "CORE_LEGADO";
        entity.solicitacaoId = solicitacaoId;
        entity.efetivacaoId = intencao.efetivacaoId().valor();
        entity.contaId = intencao.contaId().valor();
        entity.limiteChequeEspecialVigenteEsperado = intencao.limiteChequeEspecialVigenteEsperado().centavos();
        entity.limiteSolicitado = intencao.limiteSolicitado().centavos();
        entity.correlationId = intencao.correlationId().valor();
        entity.criadoEm = criadoEm;
        return entity;
    }
}
