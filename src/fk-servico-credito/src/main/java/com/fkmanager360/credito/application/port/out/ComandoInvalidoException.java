package com.fkmanager360.credito.application.port.out;

/**
 * O comando e interpretavel, mas viola uma validacao puramente local (Fase 0, passo 6 do plano
 * #0003): {@code limiteSolicitado} ausente ou nao positivo, {@code limiteVigenteVisto} ausente ou
 * negativo, {@code canalManifestacao} fora do enum, {@code observacao} acima de 500 caracteres.
 * Detectada ANTES de qualquer chamada remota -- a borda traduz para {@code 422} com o codigo
 * estavel {@code COMANDO_INVALIDO}.
 */
public class ComandoInvalidoException extends RuntimeException implements ErroDeAplicacaoComCodigo {

    public static final String CODIGO = "COMANDO_INVALIDO";

    public ComandoInvalidoException(String message) {
        super(message);
    }

    @Override
    public String codigo() {
        return CODIGO;
    }
}
