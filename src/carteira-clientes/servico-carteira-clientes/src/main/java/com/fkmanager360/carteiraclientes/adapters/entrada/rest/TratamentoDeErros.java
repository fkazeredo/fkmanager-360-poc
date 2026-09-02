package com.fkmanager360.carteiraclientes.adapters.entrada.rest;

import com.fkmanager360.carteiraclientes.aplicacao.portas.CoreLegadoIndisponivelException;
import com.fkmanager360.carteiraclientes.aplicacao.portas.CoreLegadoTimeoutException;
import com.fkmanager360.carteiraclientes.aplicacao.portas.RespostaCoreLegadoInvalidaException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Traduz as patologias da ACL do CoreLegado para a taxonomia de status HTTP da spec (ADR-0005):
 * indisponibilidade transitoria vira 503, timeout vira 504, resposta que a ACL nao sabe
 * interpretar vira 502. Nenhum destes detalhes e responsabilidade do dominio nem da aplicacao.
 */
@RestControllerAdvice
class TratamentoDeErros {

    @ExceptionHandler(CoreLegadoIndisponivelException.class)
    ProblemDetail indisponivel(CoreLegadoIndisponivelException e) {
        return problema(HttpStatus.SERVICE_UNAVAILABLE, "Nao foi possivel concluir a operacao agora, tente novamente");
    }

    @ExceptionHandler(CoreLegadoTimeoutException.class)
    ProblemDetail timeout(CoreLegadoTimeoutException e) {
        return problema(HttpStatus.GATEWAY_TIMEOUT, "Nao foi possivel concluir a operacao agora, tente novamente");
    }

    @ExceptionHandler(RespostaCoreLegadoInvalidaException.class)
    ProblemDetail respostaInvalida(RespostaCoreLegadoInvalidaException e) {
        return problema(HttpStatus.BAD_GATEWAY, "Nao foi possivel concluir a operacao agora, tente novamente");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail parametroInvalido(IllegalArgumentException e) {
        return problema(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    private static ProblemDetail problema(HttpStatus status, String mensagem) {
        return ProblemDetail.forStatusAndDetail(status, mensagem);
    }
}
