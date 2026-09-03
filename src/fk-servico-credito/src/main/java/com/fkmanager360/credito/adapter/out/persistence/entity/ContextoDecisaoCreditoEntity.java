package com.fkmanager360.credito.adapter.out.persistence.entity;

import com.fkmanager360.credito.domain.ClassificacaoRiscoCreditoBase;
import com.fkmanager360.credito.domain.ContextoDecisaoCredito;
import com.fkmanager360.credito.domain.DadosCreditoCore;
import com.fkmanager360.credito.domain.IncrementoSolicitado;
import com.fkmanager360.credito.domain.LimiteChequeEspecialVigente;
import com.fkmanager360.credito.domain.LimiteSolicitado;
import com.fkmanager360.credito.domain.SituacaoConta;
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
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

/**
 * Espelho JPA de {@code contexto_decisao_credito} (V1__criar_estado_duravel_de_credito.sql):
 * fotografia imutavel achatada de {@link ContextoDecisaoCredito} (que embute
 * {@link DadosCreditoCore}). PK = FK para {@code solicitacao_aumento_limite} (relacao 1:1 por
 * chave compartilhada) -- {@code @OneToOne @MapsId}, o padrao JPA canonico para esse desenho.
 *
 * <p>{@code @Immutable} (Hibernate): a migration documenta explicitamente que nenhum caso de uso
 * faz UPDATE nesta tabela -- uma linha nasce em TX1 e nunca e reescrita. A anotacao desliga
 * dirty-checking do Hibernate para esta entity, tornando a garantia de "nunca UPDATE" tambem uma
 * propriedade do mapeamento, nao so do codigo que o chama.
 *
 * <p>Sem {@code equals}/{@code hashCode}: nenhuma colecao deste modulo agrupa ou deduplica
 * instancias desta entity por identidade.
 */
@Entity
@Table(name = "contexto_decisao_credito")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ContextoDecisaoCreditoEntity {

    @Id
    @Column(name = "solicitacao_id")
    private UUID solicitacaoId;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "solicitacao_id")
    private SolicitacaoAumentoLimiteEntity solicitacao;

    @Column(name = "limite_cheque_especial_vigente", nullable = false)
    private long limiteChequeEspecialVigente;

    @Column(name = "situacao_conta", nullable = false)
    private String situacaoConta;

    @Column(name = "classificacao_risco_credito_base", nullable = false)
    private String classificacaoRiscoCreditoBase;

    @Column(name = "limite_solicitado", nullable = false)
    private long limiteSolicitado;

    @Column(name = "incremento_solicitado", nullable = false)
    private long incrementoSolicitado;

    @Column(name = "versao_politica_credito", nullable = false)
    private String versaoPoliticaCredito;

    @Column(name = "capturado_em", nullable = false)
    private Instant capturadoEm;

    @Column(name = "dados_credito_core_fonte", nullable = false)
    private String dadosCreditoCoreFonte;

    @Column(name = "dados_credito_core_consultado_em", nullable = false)
    private Instant dadosCreditoCoreConsultadoEm;

    public static ContextoDecisaoCreditoEntity de(
            SolicitacaoAumentoLimiteEntity solicitacao, ContextoDecisaoCredito contexto) {
        DadosCreditoCore core = contexto.dadosCreditoCore();

        ContextoDecisaoCreditoEntity entity = new ContextoDecisaoCreditoEntity();
        entity.solicitacao = solicitacao;
        entity.limiteChequeEspecialVigente = core.limiteChequeEspecialVigente().centavos();
        entity.situacaoConta = core.situacaoConta().name();
        entity.classificacaoRiscoCreditoBase = core.classificacaoRiscoCreditoBase().name();
        entity.limiteSolicitado = contexto.limiteSolicitado().centavos();
        entity.incrementoSolicitado = contexto.incrementoSolicitado().centavos();
        entity.versaoPoliticaCredito = contexto.versaoPoliticaCredito().valor();
        entity.capturadoEm = contexto.capturadoEm();
        entity.dadosCreditoCoreFonte = core.fonte();
        entity.dadosCreditoCoreConsultadoEm = core.consultadoEm();
        return entity;
    }

    public ContextoDecisaoCredito toDomain() {
        return new ContextoDecisaoCredito(
                new DadosCreditoCore(
                        new LimiteChequeEspecialVigente(limiteChequeEspecialVigente),
                        SituacaoConta.valueOf(situacaoConta),
                        ClassificacaoRiscoCreditoBase.valueOf(classificacaoRiscoCreditoBase),
                        dadosCreditoCoreConsultadoEm,
                        dadosCreditoCoreFonte),
                new LimiteSolicitado(limiteSolicitado),
                new IncrementoSolicitado(incrementoSolicitado),
                new VersaoPoliticaCredito(versaoPoliticaCredito),
                capturadoEm);
    }
}
