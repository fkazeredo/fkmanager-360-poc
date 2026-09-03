package com.fkmanager360.credito.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Decisao com consequencia formal sobre a solicitacao: resultado, motivo, a versao da
 * PoliticaCredito aplicada, o instante e o autor (CONTEXT.md de Credito). Produzida pelo
 * MotorDecisaoCredito neste ticket -- decisao humana e ParecerCredito pertencem a slices futuros.
 *
 * <p>O construtor compacto valida que {@code resultado} e coerente com {@code motivo.resultado()}.
 * Isso nunca deveria divergir -- {@link MotivoDecisaoCredito} ja carrega o resultado certo por
 * construcao -- mas e uma invariante do agregado, e falha alto (em vez de aceitar silenciosamente)
 * se algum caminho de codigo algum dia construir os dois separadamente e errar.
 */
public record DecisaoCredito(
        ResultadoDecisaoCredito resultado,
        MotivoDecisaoCredito motivo,
        VersaoPoliticaCredito versaoPoliticaCredito,
        Instant decididaEm,
        AtorOperacao autor) {

    public DecisaoCredito {
        Objects.requireNonNull(resultado, "resultado e obrigatorio");
        Objects.requireNonNull(motivo, "motivo e obrigatorio");
        Objects.requireNonNull(versaoPoliticaCredito, "versaoPoliticaCredito e obrigatoria");
        Objects.requireNonNull(decididaEm, "decididaEm e obrigatorio");
        Objects.requireNonNull(autor, "autor e obrigatorio");
        if (resultado != motivo.resultado()) {
            throw new IllegalStateException(
                    "resultado " + resultado + " diverge do resultado implicado pelo motivo " + motivo);
        }
    }
}
