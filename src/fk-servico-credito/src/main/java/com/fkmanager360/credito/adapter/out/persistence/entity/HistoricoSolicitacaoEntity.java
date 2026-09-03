package com.fkmanager360.credito.adapter.out.persistence.entity;

import com.fkmanager360.credito.application.port.out.EntradaHistorico;
import com.fkmanager360.credito.application.port.out.TipoFatoHistorico;
import com.fkmanager360.credito.domain.AtorHumano;
import com.fkmanager360.credito.domain.AtorId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Espelho JPA de {@code historico_solicitacao} (V1__criar_estado_duravel_de_credito.sql): trilha
 * funcional append-only. {@code id} e {@code GENERATED ALWAYS AS IDENTITY} -- a unica coluna
 * IDENTITY do schema --, por isso e a unica entity deste modulo com {@code @GeneratedValue}.
 * {@code fato_id} (nao o {@code id} tecnico) e a identidade LOGICA deduplicada pela
 * {@code uk_historico_fato}.
 *
 * <p>Sem relacao JPA navegavel para {@code SolicitacaoAumentoLimiteEntity} pelo mesmo motivo de
 * {@link OutboxMensagemEntity}: nenhum caso de uso atual le esta tabela de volta para o dominio.
 *
 * <p>Sem {@code equals}/{@code hashCode}: nenhuma colecao deste modulo agrupa ou deduplica
 * instancias desta entity por identidade.
 */
@Entity
@Table(name = "historico_solicitacao")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HistoricoSolicitacaoEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "solicitacao_id", nullable = false)
    private UUID solicitacaoId;

    @Column(name = "fato_id", nullable = false)
    private String fatoId;

    @Column(name = "tipo_fato", nullable = false)
    private String tipoFato;

    @Column(name = "ator_tipo", nullable = false)
    private String atorTipo;

    @Column(name = "ator_id", nullable = false)
    private String atorId;

    @Column(name = "ocorrido_em", nullable = false)
    private Instant ocorridoEm;

    public static HistoricoSolicitacaoEntity de(UUID solicitacaoId, EntradaHistorico entrada) {
        AtorColunas autor = AtorColunas.de(entrada.autor());

        HistoricoSolicitacaoEntity entity = new HistoricoSolicitacaoEntity();
        entity.solicitacaoId = solicitacaoId;
        entity.fatoId = entrada.fatoId();
        entity.tipoFato = entrada.tipoFato().name();
        entity.atorTipo = autor.tipo();
        entity.atorId = autor.id();
        entity.ocorridoEm = entrada.ocorridoEm();
        return entity;
    }

    /**
     * TX1 (plano #0003, Fase 1): o unico fato desta fase e {@code SOLICITACAO_REGISTRADA}, sempre
     * de autoria do {@link AtorHumano} que originou a submissao -- reflete exatamente o que
     * {@code inserirTx1} construia inline no adapter anterior.
     */
    public static HistoricoSolicitacaoEntity solicitacaoRegistrada(UUID solicitacaoId, AtorId originadorId, Instant ocorridoEm) {
        return de(
                solicitacaoId,
                new EntradaHistorico(
                        "SOLICITACAO:" + solicitacaoId,
                        TipoFatoHistorico.SOLICITACAO_REGISTRADA,
                        new AtorHumano(originadorId),
                        ocorridoEm));
    }
}
