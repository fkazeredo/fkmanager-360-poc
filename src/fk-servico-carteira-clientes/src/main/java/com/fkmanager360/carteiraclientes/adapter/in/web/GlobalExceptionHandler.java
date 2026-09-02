package com.fkmanager360.carteiraclientes.adapter.in.web;

import com.fkmanager360.carteiraclientes.application.port.out.CoreLegadoUnavailableException;
import com.fkmanager360.carteiraclientes.application.port.out.CoreLegadoTimeoutException;
import com.fkmanager360.carteiraclientes.application.port.out.InvalidCoreLegadoResponseException;
import com.fkmanager360.carteiraclientes.application.usecase.ContaNaoEncontradaException;
import com.fkmanager360.carteiraclientes.application.usecase.DireitoDeAtendimentoAusenteException;
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

    /**
     * Sem direito de atendimento atual, a recusa e 403 e vem daqui -- do backend dono da
     * associacao (ADR-0007, AC23). A mensagem nao distingue "Cliente inexistente" de "Cliente de
     * outra carteira": as duas respostas precisam ser indistinguiveis para quem nao tem direito.
     */
    @ExceptionHandler(DireitoDeAtendimentoAusenteException.class)
    ProblemDetail semDireitoDeAtendimento(DireitoDeAtendimentoAusenteException e) {
        return problemDetail(HttpStatus.FORBIDDEN, "Sem direito de atendimento atual sobre este Cliente");
    }

    /**
     * 404, e nao 403: a autorizacao sobre o Cliente ja passou, e a conta e que nao e dele. Nao
     * revela nada sobre contas de outros Clientes, porque a pergunta so foi feita ao Core depois
     * da autorizacao.
     */
    @ExceptionHandler(ContaNaoEncontradaException.class)
    ProblemDetail contaNaoEncontrada(ContaNaoEncontradaException e) {
        return problemDetail(HttpStatus.NOT_FOUND, "Conta nao encontrada para este Cliente");
    }

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
