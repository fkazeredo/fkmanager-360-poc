package com.fkmanager360.credito.domain;

import java.util.Objects;

/**
 * PoliticaCredito v1 (spec, secao "PoliticaCredito v1"): deterministica, avaliada em ordem, sobre
 * o ContextoDecisaoCredito congelado -- nunca acessa nada fora dele (AC33, o que faz a decisao
 * reproduzivel). Constantes em centavos, vivendo nesta propria classe: nao ha tabela
 * parametrizavel neste slice.
 *
 * <p>Regras, em ordem de precedencia:
 * <ol>
 *   <li>{@code situacaoConta != REGULAR} -&gt; {@link MotivoDecisaoCredito#CONTA_NAO_ELEGIVEL}</li>
 *   <li>{@code classificacaoRiscoCreditoBase == ALTO} -&gt;
 *       {@link MotivoDecisaoCredito#PERFIL_RISCO_INCOMPATIVEL}</li>
 *   <li>risco em {BAIXO, MEDIO} <b>e</b> limiteSolicitado &le; R$ 10.000,00 <b>e</b>
 *       incrementoSolicitado &le; R$ 2.000,00 -&gt;
 *       {@link MotivoDecisaoCredito#DENTRO_DA_POLITICA_AUTOMATICA}</li>
 *   <li>restante -&gt; {@link MotivoDecisaoCredito#FORA_DA_POLITICA_AUTOMATICA}</li>
 * </ol>
 *
 * <p>Esta versao, uma vez publicada, e semanticamente imutavel (ver Javadoc de
 * {@link VersaoPoliticaCredito}): qualquer ajuste de regra exige uma {@code PoliticaCreditoV2},
 * nunca alterar o comportamento desta classe.
 */
public final class PoliticaCreditoV1 implements PoliticaCredito {

    public static final VersaoPoliticaCredito VERSAO = new VersaoPoliticaCredito("v1");

    /** R$ 10.000,00 em centavos. */
    private static final long LIMITE_SOLICITADO_MAXIMO_CENTAVOS = 1_000_000L;

    /** R$ 2.000,00 em centavos. */
    private static final long INCREMENTO_MAXIMO_CENTAVOS = 200_000L;

    @Override
    public VersaoPoliticaCredito versao() {
        return VERSAO;
    }

    @Override
    public MotivoDecisaoCredito avaliar(ContextoDecisaoCredito contexto) {
        Objects.requireNonNull(contexto, "contexto e obrigatorio");
        DadosCreditoCore dados = contexto.dadosCreditoCore();

        if (dados.situacaoConta() != SituacaoConta.REGULAR) {
            return MotivoDecisaoCredito.CONTA_NAO_ELEGIVEL;
        }
        if (dados.classificacaoRiscoCreditoBase() == ClassificacaoRiscoCreditoBase.ALTO) {
            return MotivoDecisaoCredito.PERFIL_RISCO_INCOMPATIVEL;
        }

        boolean dentroDoTeto = contexto.limiteSolicitado().centavos() <= LIMITE_SOLICITADO_MAXIMO_CENTAVOS
                && contexto.incrementoSolicitado().centavos() <= INCREMENTO_MAXIMO_CENTAVOS;
        if (dentroDoTeto) {
            return MotivoDecisaoCredito.DENTRO_DA_POLITICA_AUTOMATICA;
        }

        return MotivoDecisaoCredito.FORA_DA_POLITICA_AUTOMATICA;
    }
}
