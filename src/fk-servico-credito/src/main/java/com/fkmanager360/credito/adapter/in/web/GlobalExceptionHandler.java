package com.fkmanager360.credito.adapter.in.web;

import com.fkmanager360.credito.application.port.out.CarteiraClientesUnavailableException;
import com.fkmanager360.credito.application.port.out.ComandoInvalidoException;
import com.fkmanager360.credito.application.port.out.ContaNaoEncontradaException;
import com.fkmanager360.credito.application.port.out.CoreLegadoTimeoutException;
import com.fkmanager360.credito.application.port.out.CoreLegadoUnavailableException;
import com.fkmanager360.credito.application.port.out.DireitoDeAtendimentoAusenteException;
import com.fkmanager360.credito.application.port.out.ErroDeAplicacaoComCodigo;
import com.fkmanager360.credito.application.port.out.IdempotenciaEmProcessamentoException;
import com.fkmanager360.credito.application.port.out.IdempotenciaFingerprintDivergenteException;
import com.fkmanager360.credito.application.port.out.InvalidCoreLegadoResponseException;
import com.fkmanager360.credito.application.port.out.LimiteSolicitadoNaoAumentaException;
import com.fkmanager360.credito.application.port.out.LimiteVigenteDesatualizadoException;
import com.fkmanager360.credito.application.port.out.SolicitacaoNaoTerminalExistenteException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.http.converter.HttpMessageNotReadableException;

/**
 * Traduz para a taxonomia de status da spec. As tres patologias da ACL do Core continuam
 * distinguiveis em protocolo -- 502 resposta invalida, 503 indisponibilidade, 504 timeout --
 * enquanto a mensagem de negocio e uma so: o gerente nao precisa saber qual das tres ocorreu,
 * e a distincao permanece onde serve, que e diagnostico e metrica.
 *
 * <p><b>Todo {@link ProblemDetail} que este handler produz carrega a propriedade {@code codigo}</b>
 * (plano #0003, secao "Envelope de erro com codigo estavel") -- inclusive os handlers ja
 * existentes de #0002 (403, 404, 502/503/504, 400 de path variable), porque
 * {@code DireitoDeAtendimentoAusenteException} e {@code ContaNaoEncontradaException} agora sao
 * lancadas TAMBEM pelo caso de uso de submissao, e {@code fk-bff-gerente} precisa de um codigo
 * uniforme em toda resposta de erro que atravessa esta fronteira, nao so nas novas. A UI nunca
 * distingue erro por texto.
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    private static final String INDISPONIVEL = "Nao foi possivel concluir a operacao agora, tente novamente";

    // --- Idempotencia (borda web: header ausente ou mal formado) -----------------------------

    @ExceptionHandler(MissingRequestHeaderException.class)
    ProblemDetail idempotencyKeyAusente(MissingRequestHeaderException e) {
        return problemDetail(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_AUSENTE", "Header Idempotency-Key e obrigatorio");
    }

    @ExceptionHandler(IdempotencyKeyInvalidaException.class)
    ProblemDetail idempotencyKeyInvalida(IdempotencyKeyInvalidaException e) {
        return problemDetail(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_INVALIDA", "Header Idempotency-Key nao e um UUID valido");
    }

    // --- Corpo estruturalmente ilegivel (Jackson) ---------------------------------------------

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail comandoIlegivel(HttpMessageNotReadableException e) {
        return problemDetail(HttpStatus.BAD_REQUEST, "COMANDO_ILEGIVEL", "Corpo da requisicao ilegivel ou estruturalmente invalido");
    }

    // --- Autorizacao de recurso e recurso inexistente (#0002, agora com codigo) ---------------

    @ExceptionHandler(DireitoDeAtendimentoAusenteException.class)
    ProblemDetail semDireitoDeAtendimento(DireitoDeAtendimentoAusenteException e) {
        return problemDetail(HttpStatus.FORBIDDEN, "SEM_DIREITO_DE_ATENDIMENTO", "Sem direito de atendimento atual sobre esta conta");
    }

    @ExceptionHandler(ContaNaoEncontradaException.class)
    ProblemDetail contaNaoEncontrada(ContaNaoEncontradaException e) {
        return problemDetail(HttpStatus.NOT_FOUND, "CONTA_NAO_ENCONTRADA", "Conta nao encontrada");
    }

    // --- Conflitos de idempotencia/unicidade e stale check (409) -------------------------------

    @ExceptionHandler(LimiteVigenteDesatualizadoException.class)
    ProblemDetail limiteVigenteDesatualizado(LimiteVigenteDesatualizadoException e) {
        return problemDetailComCodigoDaExcecao(HttpStatus.CONFLICT, e);
    }

    @ExceptionHandler(SolicitacaoNaoTerminalExistenteException.class)
    ProblemDetail solicitacaoNaoTerminalExistente(SolicitacaoNaoTerminalExistenteException e) {
        return problemDetailComCodigoDaExcecao(HttpStatus.CONFLICT, e);
    }

    @ExceptionHandler(IdempotenciaEmProcessamentoException.class)
    ProblemDetail idempotenciaEmProcessamento(IdempotenciaEmProcessamentoException e) {
        return problemDetailComCodigoDaExcecao(HttpStatus.CONFLICT, e);
    }

    // --- Comando semanticamente invalido (422) --------------------------------------------------

    @ExceptionHandler(ComandoInvalidoException.class)
    ProblemDetail comandoInvalido(ComandoInvalidoException e) {
        return problemDetailComCodigoDaExcecao(HttpStatus.UNPROCESSABLE_ENTITY, e);
    }

    @ExceptionHandler(LimiteSolicitadoNaoAumentaException.class)
    ProblemDetail limiteSolicitadoNaoAumenta(LimiteSolicitadoNaoAumentaException e) {
        return problemDetailComCodigoDaExcecao(HttpStatus.UNPROCESSABLE_ENTITY, e);
    }

    @ExceptionHandler(IdempotenciaFingerprintDivergenteException.class)
    ProblemDetail idempotenciaFingerprintDivergente(IdempotenciaFingerprintDivergenteException e) {
        return problemDetailComCodigoDaExcecao(HttpStatus.UNPROCESSABLE_ENTITY, e);
    }

    // --- Dependencias externas (#0002, agora com codigo) ----------------------------------------

    @ExceptionHandler(InvalidCoreLegadoResponseException.class)
    ProblemDetail respostaInvalidaDoCore(InvalidCoreLegadoResponseException e) {
        return problemDetail(HttpStatus.BAD_GATEWAY, "DEPENDENCIA_INDISPONIVEL", INDISPONIVEL);
    }

    @ExceptionHandler({CoreLegadoUnavailableException.class, CarteiraClientesUnavailableException.class})
    ProblemDetail dependenciaIndisponivel(RuntimeException e) {
        return problemDetail(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCIA_INDISPONIVEL", INDISPONIVEL);
    }

    @ExceptionHandler(CoreLegadoTimeoutException.class)
    ProblemDetail timeoutDoCore(CoreLegadoTimeoutException e) {
        return problemDetail(HttpStatus.GATEWAY_TIMEOUT, "DEPENDENCIA_INDISPONIVEL", INDISPONIVEL);
    }

    // --- Path variable fora do formato (#0002, agora com codigo) --------------------------------

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail parametroInvalido(IllegalArgumentException e) {
        return problemDetail(HttpStatus.BAD_REQUEST, "IDENTIFICADOR_INVALIDO", e.getMessage());
    }

    // Nota: SolicitacaoNaoEncontradaException nao tem handler dedicado -- nao deveria surgir no
    // fluxo normal (Javadoc da propria excecao), e deliberadamente nao implementa
    // ErroDeAplicacaoComCodigo. Sem handler aqui, o Spring produz o seu 500 default, sem `codigo`
    // especifico da tabela -- escolha documentada, nao omissao.

    private static <T extends RuntimeException & ErroDeAplicacaoComCodigo> ProblemDetail problemDetailComCodigoDaExcecao(
            HttpStatus status, T e) {
        return problemDetail(status, e.codigo(), e.getMessage());
    }

    private static ProblemDetail problemDetail(HttpStatus status, String codigo, String mensagem) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, mensagem);
        problemDetail.setProperty("codigo", codigo);
        return problemDetail;
    }
}
