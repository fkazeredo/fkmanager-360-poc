package com.fkmanager360.bffgerente.adapter.in.web;

/**
 * Uma dependencia respondeu {@code 2xx}, mas com um corpo incompleto ou de forma que a
 * composicao nao sabe interpretar -- campo obrigatorio ausente ou nulo. Diferente de um erro
 * HTTP explicito (403, 404, 5xx), esta e uma falha de <b>contrato</b>: o parceiro disse "sucesso"
 * e nao entregou o que prometeu. A borda traduz para 502, nunca para 500 nem para os dados
 * incompletos seguindo adiante como se fossem validos (por exemplo, um limite ausente virando
 * silenciosamente zero).
 */
class DependenciaRespostaInvalidaException extends RuntimeException {

    DependenciaRespostaInvalidaException(String message) {
        super(message);
    }
}
