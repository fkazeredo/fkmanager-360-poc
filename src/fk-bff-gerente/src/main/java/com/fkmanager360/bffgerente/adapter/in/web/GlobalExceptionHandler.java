package com.fkmanager360.bffgerente.adapter.in.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * A taxonomia de erro completa desta fronteira (achado I1 do review de #0002). Quatro origens
 * distintas, cada uma com o status que lhe cabe -- nenhuma pode escapar para um 500 generico:
 *
 * <ul>
 *   <li><b>entrada invalida do proprio BFF</b> -- um path variable fora do formato esperado,
 *       recusado antes de qualquer chamada remota -- vira 400;</li>
 *   <li><b>401 de um Resource Server</b> -- o token <i>delegado</i> foi recusado, nao a sessao do
 *       browser -- NAO vira 401 (isso confundiria "usuario precisa logar de novo" com "a cadeia
 *       de Token Exchange quebrou"); vira 502, taxonomia de integracao. A sessao do BFF continua
 *       valida, e quem decide se o browser precisa de login e o proprio filtro de seguranca do
 *       BFF, antes deste advice ser alcancado;</li>
 *   <li><b>403/404 de um Resource Server</b> -- resposta de negocio do backend dono do recurso
 *       (ADR-0007) -- atravessa com o mesmo significado, sem reinterpretacao;</li>
 *   <li><b>qualquer outra coisa</b> -- 5xx, timeout, reset, corpo malformado, ou um 4xx que o BFF
 *       nao esperava -- e falha de integracao e vira a mensagem unica de indisponibilidade; o
 *       gerente nao precisa saber qual dependencia caiu, e a distincao permanece em protocolo,
 *       metrica e diagnostico.</li>
 * </ul>
 */
@RestControllerAdvice(basePackages = "com.fkmanager360.bffgerente.adapter.in.web")
class GlobalExceptionHandler {

    private static final String INDISPONIVEL = "Nao foi possivel concluir a operacao agora, tente novamente";

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail entradaInvalida(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(HttpClientErrorException.Forbidden.class)
    ProblemDetail semDireitoDeAtendimento(HttpClientErrorException.Forbidden e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Sem direito de atendimento atual");
    }

    @ExceptionHandler(HttpClientErrorException.NotFound.class)
    ProblemDetail naoEncontrado(HttpClientErrorException.NotFound e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Recurso nao encontrado");
    }

    /**
     * Um Resource Server recusou o token <i>delegado</i> que o BFF apresentou -- allow-list,
     * scope, audience ou expiracao da cadeia de Token Exchange. Isto nao e "o usuario nao esta
     * autenticado": a sessao do browser continua valida, e reencaminhar como 401 faria a SPA
     * tentar reautenticar um usuario que ja esta logado, escondendo uma falha de integracao atras
     * de um sintoma que aponta para o lugar errado.
     */
    @ExceptionHandler(HttpClientErrorException.Unauthorized.class)
    ProblemDetail tokenDelegadoRecusado(HttpClientErrorException.Unauthorized e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, INDISPONIVEL);
    }

    /**
     * Um corpo {@code 2xx} incompleto, ou qualquer outro {@code 4xx} downstream que as regras
     * acima nao esperavam (por exemplo, um 400 que a validacao de borda do proprio BFF nao pegou
     * mas o backend dono do recurso pegou): falha de integracao, nao erro do usuario.
     */
    @ExceptionHandler({HttpClientErrorException.class, DependenciaRespostaInvalidaException.class})
    ProblemDetail respostaDownstreamInesperada(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, INDISPONIVEL);
    }

    @ExceptionHandler({HttpServerErrorException.class, ResourceAccessException.class})
    ProblemDetail dependenciaIndisponivel(Exception e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, INDISPONIVEL);
    }
}
