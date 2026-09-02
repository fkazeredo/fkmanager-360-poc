package com.fkmanager360.credito.adapter.in.web;

import com.fkmanager360.credito.application.port.out.CarteiraClientesUnavailableException;
import com.fkmanager360.credito.application.port.out.ContaNaoEncontradaException;
import com.fkmanager360.credito.application.port.out.CoreLegadoTimeoutException;
import com.fkmanager360.credito.application.port.out.CoreLegadoUnavailableException;
import com.fkmanager360.credito.application.port.out.DireitoDeAtendimentoAusenteException;
import com.fkmanager360.credito.application.port.out.InvalidCoreLegadoResponseException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Traduz para a taxonomia de status da spec. As tres patologias da ACL do Core continuam
 * distinguiveis em protocolo -- 502 resposta invalida, 503 indisponibilidade, 504 timeout --
 * enquanto a mensagem de negocio e uma so: o gerente nao precisa saber qual das tres ocorreu,
 * e a distincao permanece onde serve, que e diagnostico e metrica.
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    private static final String INDISPONIVEL = "Nao foi possivel concluir a operacao agora, tente novamente";

    @ExceptionHandler(DireitoDeAtendimentoAusenteException.class)
    ProblemDetail semDireitoDeAtendimento(DireitoDeAtendimentoAusenteException e) {
        return problemDetail(HttpStatus.FORBIDDEN, "Sem direito de atendimento atual sobre esta conta");
    }

    @ExceptionHandler(ContaNaoEncontradaException.class)
    ProblemDetail contaNaoEncontrada(ContaNaoEncontradaException e) {
        return problemDetail(HttpStatus.NOT_FOUND, "Conta nao encontrada");
    }

    @ExceptionHandler(InvalidCoreLegadoResponseException.class)
    ProblemDetail respostaInvalidaDoCore(InvalidCoreLegadoResponseException e) {
        return problemDetail(HttpStatus.BAD_GATEWAY, INDISPONIVEL);
    }

    @ExceptionHandler({CoreLegadoUnavailableException.class, CarteiraClientesUnavailableException.class})
    ProblemDetail dependenciaIndisponivel(RuntimeException e) {
        return problemDetail(HttpStatus.SERVICE_UNAVAILABLE, INDISPONIVEL);
    }

    @ExceptionHandler(CoreLegadoTimeoutException.class)
    ProblemDetail timeoutDoCore(CoreLegadoTimeoutException e) {
        return problemDetail(HttpStatus.GATEWAY_TIMEOUT, INDISPONIVEL);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail parametroInvalido(IllegalArgumentException e) {
        return problemDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    private static ProblemDetail problemDetail(HttpStatus status, String mensagem) {
        return ProblemDetail.forStatusAndDetail(status, mensagem);
    }
}
