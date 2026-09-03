package com.fkmanager360.credito.adapter.out.persistence.entity;

import com.fkmanager360.credito.domain.AtorOperacao;
import com.fkmanager360.credito.domain.DecisaoCredito;
import com.fkmanager360.credito.domain.MotivoDecisaoCredito;
import com.fkmanager360.credito.domain.ResultadoDecisaoCredito;
import com.fkmanager360.credito.domain.VersaoPoliticaCredito;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Espelho JPA de {@code decisao_credito} (V1__criar_estado_duravel_de_credito.sql). PK = FK para
 * {@code solicitacao_aumento_limite} -- no maximo uma {@link DecisaoCredito} por
 * SolicitacaoAumentoLimite (mesmo padrao {@code @OneToOne @MapsId} de
 * {@link ContextoDecisaoCreditoEntity}), garantia que soma-se ao {@code FOR UPDATE NOWAIT} de TX2
 * para impedir duas decisoes concorrentes sobre a mesma solicitacao.
 *
 * <p>Sem {@code equals}/{@code hashCode}: nenhuma colecao deste modulo agrupa ou deduplica
 * instancias desta entity por identidade.
 */
@Entity
@Table(name = "decisao_credito")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DecisaoCreditoEntity {

    @Id
    @Column(name = "solicitacao_id")
    private UUID solicitacaoId;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "solicitacao_id")
    private SolicitacaoAumentoLimiteEntity solicitacao;

    @Column(name = "resultado", nullable = false)
    private String resultado;

    @Column(name = "motivo", nullable = false)
    private String motivo;

    @Column(name = "versao_politica_credito", nullable = false)
    private String versaoPoliticaCredito;

    @Column(name = "decidida_em", nullable = false)
    private Instant decididaEm;

    @Column(name = "autor_tipo", nullable = false)
    private String autorTipo;

    @Column(name = "autor_id", nullable = false)
    private String autorId;

    public static DecisaoCreditoEntity de(SolicitacaoAumentoLimiteEntity solicitacao, DecisaoCredito decisao) {
        AtorColunas autor = AtorColunas.de(decisao.autor());

        DecisaoCreditoEntity entity = new DecisaoCreditoEntity();
        entity.solicitacao = solicitacao;
        entity.resultado = decisao.resultado().name();
        entity.motivo = decisao.motivo().name();
        entity.versaoPoliticaCredito = decisao.versaoPoliticaCredito().valor();
        entity.decididaEm = decisao.decididaEm();
        entity.autorTipo = autor.tipo();
        entity.autorId = autor.id();
        return entity;
    }

    public DecisaoCredito toDomain() {
        AtorOperacao autor = AtorColunas.paraAtorOperacao(autorTipo, autorId);
        return new DecisaoCredito(
                ResultadoDecisaoCredito.valueOf(resultado),
                MotivoDecisaoCredito.valueOf(motivo),
                new VersaoPoliticaCredito(versaoPoliticaCredito),
                decididaEm,
                autor);
    }
}
