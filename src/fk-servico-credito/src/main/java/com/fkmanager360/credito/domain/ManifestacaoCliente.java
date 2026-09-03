package com.fkmanager360.credito.domain;

import java.util.Objects;

/**
 * Como a manifestacao do Cliente chegou ao GerenteRelacionamento: o CanalManifestacao (obrigatorio)
 * e uma observacao opcional do gerente (CONTEXT.md de Credito, AC27). Distinta da
 * OrigemSolicitacao, que diz quem originou -- e deliberadamente nao prova juridica.
 *
 * <p>{@code observacao} sofre trim; vazia apos o trim <b>nao</b> e uma String vazia persistida,
 * e sim ausencia ({@code null}) -- isso e o que impede o storage de gravar uma observacao "em
 * branco" que nada acrescenta. Acima de 500 caracteres apos o trim e recusado.
 */
public record ManifestacaoCliente(CanalManifestacao canalManifestacao, String observacao) {

    private static final int TAMANHO_MAXIMO_OBSERVACAO = 500;

    public ManifestacaoCliente {
        Objects.requireNonNull(canalManifestacao, "canalManifestacao e obrigatorio");

        if (observacao != null) {
            String tratada = observacao.trim();
            observacao = tratada.isEmpty() ? null : tratada;
        }
        if (observacao != null && observacao.length() > TAMANHO_MAXIMO_OBSERVACAO) {
            throw new IllegalArgumentException(
                    "observacao nao pode exceder " + TAMANHO_MAXIMO_OBSERVACAO
                            + " caracteres apos trim: " + observacao.length());
        }
    }
}
