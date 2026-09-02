package com.fkmanager360.bffgerente.adapter.in.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * O BFF nao decide autorizacao de recurso: quando um Resource Server recusa, a recusa apenas
 * atravessa com o mesmo significado (ADR-0007). Falhas de comunicacao com qualquer um dos dois
 * servicos viram a mensagem unica de indisponibilidade -- o gerente nao precisa saber qual
 * dependencia caiu, e a distincao permanece em protocolo, metrica e diagnostico.
 */
@RestControllerAdvice(basePackages = "com.fkmanager360.bffgerente.adapter.in.web")
class GlobalExceptionHandler {

    private static final String INDISPONIVEL = "Nao foi possivel concluir a operacao agora, tente novamente";

    @ExceptionHandler(HttpClientErrorException.Forbidden.class)
    ProblemDetail semDireitoDeAtendimento(HttpClientErrorException.Forbidden e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Sem direito de atendimento atual");
    }

    @ExceptionHandler(HttpClientErrorException.NotFound.class)
    ProblemDetail naoEncontrado(HttpClientErrorException.NotFound e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Recurso nao encontrado");
    }

    @ExceptionHandler({HttpServerErrorException.class, ResourceAccessException.class})
    ProblemDetail dependenciaIndisponivel(Exception e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, INDISPONIVEL);
    }
}
