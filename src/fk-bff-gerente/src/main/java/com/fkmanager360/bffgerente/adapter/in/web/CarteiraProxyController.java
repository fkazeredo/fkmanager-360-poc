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
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * A listagem da carteira: encaminhamento autenticado, sem composicao -- a tela nao precisa de
 * mais nada aqui, e encaminhar e resposta legitima do BFF quando nao ha o que compor (ADR-0013).
 * A composicao de verdade esta em {@link AtendimentoController}.
 *
 * <p>O BFF nunca fala com simulador-core-legado (AC30) e nunca substitui a autorizacao de recurso
 * feita pelo servico dono do recurso (ADR-0007): o 403 de CarteiraClientes so atravessa.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "carteira", description = "Listagem da CarteiraClientes (proxy autenticado com Token Exchange).")
public class CarteiraProxyController {

    private final RestClient carteiraClientesRestClient;
    private final DelegatedTokenResolver tokenResolver;

    @Operation(
            operationId = "listarClientesDaCarteira",
            summary = "Pagina da CarteiraClientes do gerente autenticado",
            description = "Proxy autenticado para fk-servico-carteira-clientes: o BFF obtem por Token Exchange "
                    + "um token com audience restrita a servico-carteira-clientes (ADR-0015) e repassa a "
                    + "chamada. O BFF nunca fala com fk-simulador-core-legado (AC30) nem reimplementa a "
                    + "autorizacao de recurso, que pertence ao servico dono (ADR-0007).")
    @SecurityRequirement(name = "cookieSessao")
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Pagina da carteira do gerente autenticado. Corpo JSON opaco de "
                            + "fk-servico-carteira-clientes, repassado sem tipagem no BFF.",
                    content = @Content(schema = @Schema(type = "object", description = "Corpo cru repassado sem desserializacao no BFF."))),
            @ApiResponse(responseCode = "401", description = "Sem sessao autenticada.", content = @Content),
            @ApiResponse(responseCode = "403",
                    description = "Sem direito de atendimento atual, segundo fk-servico-carteira-clientes. Como "
                            + "esse servico ainda nao publica codigo, esta recusa hoje surge tipicamente como "
                            + "502 DEPENDENCIA_INDISPONIVEL.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = GlobalExceptionHandler.EnvelopeErroPublico.class))),
            @ApiResponse(responseCode = "502",
                    description = "Resposta de fk-servico-carteira-clientes nao reconhecida pela allow-list "
                            + "deste BFF (inclui hoje qualquer 403/404, ate aquele servico publicar codigo).",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = GlobalExceptionHandler.EnvelopeErroPublico.class))),
            @ApiResponse(responseCode = "503",
                    description = "fk-servico-carteira-clientes indisponivel ou inalcancavel no momento.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = GlobalExceptionHandler.EnvelopeErroPublico.class))),
    })
    @GetMapping(path = "/api/carteira/clientes", produces = "application/json")
    String listarClientesDaCarteira(
            @Parameter(hidden = true) Authentication authentication,
            @Parameter(hidden = true) HttpServletRequest request,
            @Parameter(hidden = true) HttpServletResponse response,
            // name= explicito: o parametro de query publico ("pagina"/"tamanho") e contrato HTTP
            // ja exercitado por Angular/Playwright/curl -- so o identificador Java interno segue
            // a convencao tecnica em ingles.
            @Parameter(description = "Pagina zero-based.",
                    schema = @Schema(type = "integer", minimum = "0", defaultValue = "0"))
            @RequestParam(name = "pagina", defaultValue = "0") int page,
            @Parameter(description = "Tamanho de pagina. Limite e o mesmo de fk-servico-carteira-clientes.",
                    schema = @Schema(type = "integer", minimum = "1", maximum = "100", defaultValue = "20"))
            @RequestParam(name = "tamanho", required = false) Integer size) {

        String delegatedToken = tokenResolver.tokenPara(
                TokenExchangeConfig.REGISTRATION_CARTEIRA_CLIENTES, authentication, request, response);

        return carteiraClientesRestClient.get()
                .uri(uriBuilder -> {
                    uriBuilder.path("/carteira/clientes").queryParam("pagina", page);
                    if (size != null) {
                        uriBuilder.queryParam("tamanho", size);
                    }
                    return uriBuilder.build();
                })
                .header("Authorization", "Bearer " + delegatedToken)
                .retrieve()
                .body(String.class);
    }
}
