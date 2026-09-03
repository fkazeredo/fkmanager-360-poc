package com.fkmanager360.bffgerente.adapter.in.web;

import com.fkmanager360.bffgerente.config.DelegatedTokenResolver;
import com.fkmanager360.bffgerente.config.TokenExchangeConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;


/**
 * Proxy autenticado da submissao (plano #0003, secao 9 "bff-gerente"). Encaminhamento puro, sem
 * composicao: o corpo e o header {@code Idempotency-Key} atravessam INTACTOS para servico-credito
 * -- este controller nao gera, nao regenera e nao reinterpreta nenhum dos dois (spec, secao
 * "Idempotencia da submissao"). Por isso o corpo trafega como {@code String} cru, nunca
 * desserializado aqui: o BFF nao precisa (nem deve) conhecer a forma do comando para repassa-lo.
 *
 * <p>Usa {@link TokenExchangeConfig#REGISTRATION_CREDITO_ESCRITA} -- registration distinta da que
 * {@link AtendimentoController} usa para o GET do limite vigente ({@code REGISTRATION_CREDITO_LEITURA}):
 * least privilege por operacao, o token delegado desta chamada carrega {@code credito.escrita},
 * nunca {@code credito.leitura}.
 *
 * <p>O status HTTP devolvido por Credito ({@code 201} criacao, {@code 200} replay) e propagado tal
 * como veio; o corpo de sucesso tambem e cru. Erros (4xx/5xx) sao tratados por
 * {@link GlobalExceptionHandler}, que produz o envelope publico proprio do BFF -- nunca repassa o
 * {@code ProblemDetail} de Credito verbatim.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "solicitacoes", description = "Proxy autenticado da submissao da SolicitacaoAumentoLimite -- corpo "
        + "e Idempotency-Key repassados intactos para fk-servico-credito.")
public class SolicitacaoAumentoLimiteProxyController {

    private final RestClient creditoRestClient;
    private final DelegatedTokenResolver tokenResolver;

    @Operation(
            operationId = "submeterSolicitacaoAumentoLimite",
            summary = "Proxy autenticado da submissao da SolicitacaoAumentoLimite",
            description = "Encaminhamento puro para fk-servico-credito, com token trocado por Token Exchange "
                    + "(credito.escrita -- registration distinta da usada no GET do limite vigente, least "
                    + "privilege por operacao). Corpo e Idempotency-Key atravessam INTACTOS: o BFF nao gera, "
                    + "nao regenera e nao reinterpreta nenhum dos dois. Status HTTP de sucesso (201/200) e o "
                    + "corpo sao propagados tal como fk-servico-credito devolveu. Escrita real, sujeita a CSRF "
                    + "(AC20).")
    @SecurityRequirement(name = "cookieSessao")
    @ApiResponses({
            @ApiResponse(responseCode = "201",
                    description = "SolicitacaoAumentoLimite criada agora (independentemente do resultado da decisao).",
                    content = @Content(schema = @Schema(type = "object", description = "Corpo cru propagado de fk-servico-credito, sem tipagem no BFF."))),
            @ApiResponse(responseCode = "200",
                    description = "Replay idempotente -- mesma chave, mesmo fingerprint, operacao ja concluida.",
                    content = @Content(schema = @Schema(type = "object", description = "Corpo cru propagado de fk-servico-credito, sem tipagem no BFF."))),
            @ApiResponse(responseCode = "400",
                    description = "IDEMPOTENCY_KEY_AUSENTE (header ausente, recusado pelo proprio BFF), "
                            + "IDEMPOTENCY_KEY_INVALIDA ou COMANDO_ILEGIVEL (fk-servico-credito), ou "
                            + "IDENTIFICADOR_INVALIDO (clienteId/contaId fora do formato, recusado pelo proprio BFF).",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = GlobalExceptionHandler.EnvelopeErroPublico.class))),
            @ApiResponse(responseCode = "401", description = "Sem sessao autenticada.", content = @Content),
            @ApiResponse(responseCode = "403",
                    description = "Sem direito de atendimento atual. codigo = SEM_DIREITO_DE_ATENDIMENTO.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = GlobalExceptionHandler.EnvelopeErroPublico.class))),
            @ApiResponse(responseCode = "404",
                    description = "A conta nao e reconhecida pelo CoreLegado. codigo = CONTA_NAO_ENCONTRADA.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = GlobalExceptionHandler.EnvelopeErroPublico.class))),
            @ApiResponse(responseCode = "409",
                    description = "LIMITE_VIGENTE_DESATUALIZADO, SOLICITACAO_NAO_TERMINAL_EXISTENTE ou IDEMPOTENCIA_EM_PROCESSAMENTO.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = GlobalExceptionHandler.EnvelopeErroPublico.class))),
            @ApiResponse(responseCode = "422",
                    description = "COMANDO_INVALIDO, LIMITE_SOLICITADO_NAO_AUMENTA ou IDEMPOTENCIA_FINGERPRINT_DIVERGENTE.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = GlobalExceptionHandler.EnvelopeErroPublico.class))),
            @ApiResponse(responseCode = "502",
                    description = "codigo desconhecido, corpo upstream ilegivel, ou token delegado recusado (401 "
                            + "da cadeia de Token Exchange -- nunca vira 401 para o browser). codigo = DEPENDENCIA_INDISPONIVEL.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = GlobalExceptionHandler.EnvelopeErroPublico.class))),
            @ApiResponse(responseCode = "503",
                    description = "fk-servico-credito indisponivel. codigo = DEPENDENCIA_INDISPONIVEL.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = GlobalExceptionHandler.EnvelopeErroPublico.class))),
    })
    @PostMapping(path = "/api/clientes/{clienteId}/contas/{contaId}/solicitacoes-aumento-limite", produces = "application/json")
    ResponseEntity<String> submeter(
            @Parameter(description = "Identificador do Cliente selecionado.", example = "1",
                    schema = @Schema(pattern = "^[0-9]{1,10}$"))
            @PathVariable String clienteId,
            @Parameter(description = "Identificador da ContaCorrente.", example = "10001",
                    schema = @Schema(pattern = "^[0-9]{1,10}$"))
            @PathVariable String contaId,
            @Parameter(description = "Repassado intacto para fk-servico-credito -- nunca gerado ou "
                    + "reinterpretado aqui.", required = true, schema = @Schema(format = "uuid"))
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Repassado intacto para fk-servico-credito -- o BFF nao desserializa este corpo.",
                    required = true,
                    content = @Content(schema = @Schema(type = "object", description = "Corpo cru, nao desserializado no BFF.")))
            @RequestBody String corpoCru,
            @Parameter(hidden = true) Authentication authentication,
            @Parameter(hidden = true) HttpServletRequest request,
            @Parameter(hidden = true) HttpServletResponse response) {

        IdentificadorHost.validar(clienteId, "clienteId");
        IdentificadorHost.validar(contaId, "contaId");

        String tokenDelegado = tokenResolver.tokenPara(
                TokenExchangeConfig.REGISTRATION_CREDITO_ESCRITA, authentication, request, response);

        ResponseEntity<String> upstream = creditoRestClient.post()
                .uri("/clientes/{clienteId}/contas/{contaId}/solicitacoes-aumento-limite", clienteId, contaId)
                .header("Authorization", "Bearer " + tokenDelegado)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(corpoCru)
                .retrieve()
                .toEntity(String.class);

        // So status + corpo cru atravessam (Javadoc da classe) -- NUNCA o HeaderMap inteiro de
        // upstream. `.toEntity(...)` inclui headers hop-by-hop de servico-credito (em particular
        // `Transfer-Encoding: chunked`, quando a resposta upstream usa chunked); devolve-los aqui
        // fazia o container adicionar o SEU PROPRIO `Transfer-Encoding: chunked` por cima,
        // resultando numa resposta HTTP com o header duplicado -- nginx recusa isso com `502
        // upstream sent duplicate header line` (bug real, encontrado pelo Playwright contra a
        // stack real, corrigido nesta mesma etapa). Content-Length/Transfer-Encoding da resposta
        // final ficam a cargo do proprio container, a partir do corpo que de fato escrevemos.
        return ResponseEntity.status(upstream.getStatusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(upstream.getBody());
    }
}
