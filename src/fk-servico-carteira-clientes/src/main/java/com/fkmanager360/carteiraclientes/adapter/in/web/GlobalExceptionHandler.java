package com.fkmanager360.carteiraclientes.adapter.in.web;

import com.fkmanager360.carteiraclientes.application.port.out.CoreLegadoUnavailableException;
import com.fkmanager360.carteiraclientes.application.port.out.CoreLegadoTimeoutException;
import com.fkmanager360.carteiraclientes.application.port.out.InvalidCoreLegadoResponseException;
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
class GlobalExceptionHandler {

    @ExceptionHandler(CoreLegadoUnavailableException.class)
    ProblemDetail unavailable(CoreLegadoUnavailableException e) {
        return problemDetail(HttpStatus.SERVICE_UNAVAILABLE, "Nao foi possivel concluir a operacao agora, tente novamente");
    }

    @ExceptionHandler(CoreLegadoTimeoutException.class)
    ProblemDetail timeout(CoreLegadoTimeoutException e) {
        return problemDetail(HttpStatus.GATEWAY_TIMEOUT, "Nao foi possivel concluir a operacao agora, tente novamente");
    }

    @ExceptionHandler(InvalidCoreLegadoResponseException.class)
    ProblemDetail invalidResponse(InvalidCoreLegadoResponseException e) {
        return problemDetail(HttpStatus.BAD_GATEWAY, "Nao foi possivel concluir a operacao agora, tente novamente");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalidParameter(IllegalArgumentException e) {
        return problemDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    private static ProblemDetail problemDetail(HttpStatus status, String mensagem) {
        return ProblemDetail.forStatusAndDetail(status, mensagem);
    }
}
