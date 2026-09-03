package com.fkmanager360.credito.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Fotografia imutavel dos fatos considerados no momento da submissao, junto com a
 * {@code versaoPoliticaCredito} aplicada (ADR-0006, CONTEXT.md de Credito). Contem somente o que a
 * decisao usou e o que permite reproduzi-la.
 *
 * <p><b>Fora do contexto, deliberadamente</b> (AC33): {@code clienteId}, {@code contaId},
 * {@code originadorId}, qualquer evidencia de autorizacao e qualquer dado cadastral. Esses campos
 * pertencem a SolicitacaoAumentoLimite, porque respondem "quem podia operar" -- uma pergunta
 * diferente de "com quais fatos se decidiu".
 *
 * <p>{@link DadosCreditoCore} fica embutido, e nao achatado, porque a procedencia (instante da
 * consulta e fonte) e registrada uma unica vez para o conjunto de fatos externos, e nao repetida
 * campo a campo.
 */
public record ContextoDecisaoCredito(
        DadosCreditoCore dadosCreditoCore,
        LimiteSolicitado limiteSolicitado,
        IncrementoSolicitado incrementoSolicitado,
        VersaoPoliticaCredito versaoPoliticaCredito,
        Instant capturadoEm) {

    public ContextoDecisaoCredito {
        Objects.requireNonNull(dadosCreditoCore, "dadosCreditoCore e obrigatorio");
        Objects.requireNonNull(limiteSolicitado, "limiteSolicitado e obrigatorio");
        Objects.requireNonNull(incrementoSolicitado, "incrementoSolicitado e obrigatorio");
        Objects.requireNonNull(versaoPoliticaCredito, "versaoPoliticaCredito e obrigatoria");
        Objects.requireNonNull(capturadoEm, "capturadoEm e obrigatorio");
    }

    /**
     * Unica forma publica de construir um ContextoDecisaoCredito: calcula o
     * {@code incrementoSolicitado} a partir do vigente embutido em {@code dadosCreditoCore} e do
     * {@code limiteSolicitado} recebido, e falha explicitamente se o resultado nao for
     * estritamente positivo.
     *
     * <p>Esta e a <b>segunda linha de defesa</b>: a spec exige que a comparacao
     * "{@code limiteSolicitado > vigente}" (passo 10 da Fase 0) ja tenha acontecido na camada de
     * aplicacao antes desta chamada, mas este factory nunca confia nisso silenciosamente -- se por
     * qualquer motivo o incremento nao for positivo, um ContextoDecisaoCredito inconsistente
     * jamais chega a existir.
     */
    public static ContextoDecisaoCredito congelar(
            DadosCreditoCore dadosCreditoCore,
            LimiteSolicitado limiteSolicitado,
            VersaoPoliticaCredito versaoPoliticaCredito,
            Instant capturadoEm) {
        Objects.requireNonNull(dadosCreditoCore, "dadosCreditoCore e obrigatorio");
        Objects.requireNonNull(limiteSolicitado, "limiteSolicitado e obrigatorio");
        Objects.requireNonNull(versaoPoliticaCredito, "versaoPoliticaCredito e obrigatoria");
        Objects.requireNonNull(capturadoEm, "capturadoEm e obrigatorio");

        long limiteVigenteCentavos = dadosCreditoCore.limiteChequeEspecialVigente().centavos();
        long incrementoCentavos = limiteSolicitado.centavos() - limiteVigenteCentavos;
        if (incrementoCentavos <= 0) {
            throw new IllegalArgumentException(
                    "incrementoSolicitado deve ser positivo: limiteSolicitado=" + limiteSolicitado.centavos()
                            + " limiteChequeEspecialVigente=" + limiteVigenteCentavos);
        }

        return new ContextoDecisaoCredito(
                dadosCreditoCore,
                limiteSolicitado,
                new IncrementoSolicitado(incrementoCentavos),
                versaoPoliticaCredito,
                capturadoEm);
    }
}
