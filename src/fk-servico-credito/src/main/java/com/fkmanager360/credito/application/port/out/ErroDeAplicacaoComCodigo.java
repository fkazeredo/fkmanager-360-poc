package com.fkmanager360.credito.application.port.out;

/**
 * Contrato comum das excecoes tipadas de aplicacao que a borda web (proxima etapa) traduz para
 * {@code ProblemDetail} com um codigo estavel e nunca uma frase de interface (plano #0003, secao
 * "Envelope de erro com codigo estavel"). O {@code codigo()} e o valor exato que o
 * {@code GlobalExceptionHandler} da proxima etapa usa -- a UI nunca distingue erro por texto.
 */
public interface ErroDeAplicacaoComCodigo {

    String codigo();
}
