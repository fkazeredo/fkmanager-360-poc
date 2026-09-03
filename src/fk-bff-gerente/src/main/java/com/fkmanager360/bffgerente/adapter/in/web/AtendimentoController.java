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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;


/**
 * A composicao da tela de atendimento (AC30): o modelo de apresentacao e montado <b>aqui</b>, a
 * partir de servico-carteira-clientes (identidade e vinculo) e servico-credito (limite vigente),
 * porque nenhum dos dois contextos precisa conhecer dado que nao e seu (ADR-0004, ADR-0013).
 *
 * <p>O resultado e modelo de apresentacao, nao agregado. O BFF nao implementa regra de credito,
 * nao fala com o simulador-core-legado e nao substitui a autorizacao de recurso feita pelos
 * servicos: cada um dos dois verifica o direito de atendimento por conta propria, e um 403 vindo
 * de qualquer um deles apenas atravessa (ADR-0007).
 *
 * <p>O {@code clienteId} viaja no caminho porque a verificacao de direito acontece por Cliente e
 * precisa preceder qualquer acesso ao Core (AC23). Ele nao e afirmacao de posse: quem confirma
 * que a conta e daquele Cliente e CarteiraClientes, contra o CoreLegado.
 *
 * <p><b>DEFERRED (achado C4 do review de #0002).</b> As duas chamadas remotas em {@link #atendimento}
 * sao independentes uma da outra e poderiam ser paralelizadas -- reduzindo a latencia da tela ao
 * maior dos dois tempos, em vez da soma. Isso exige resolver os dois tokens delegados no thread da
 * requisicao (o resolver depende dos atributos {@link HttpServletRequest}/{@link HttpServletResponse}
 * da requisicao atual) antes de despachar as chamadas por um executor que propague o contexto de
 * seguranca -- complexidade real de concorrencia, desproporcional ao ganho numa POC com um unico
 * par de chamadas nesta rota. Adiado deliberadamente; nao e um descuido.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "atendimento", description = "Contas do Cliente e a composicao da tela de atendimento a partir dos dois contextos.")
public class AtendimentoController {

    private final RestClient carteiraClientesRestClient;
    private final RestClient creditoRestClient;
    private final DelegatedTokenResolver tokenResolver;

    @Operation(
            operationId = "listarContasDoCliente",
            summary = "ContaCorrentes do Cliente selecionado",
            description = "Encaminhamento autenticado para fk-servico-carteira-clientes, com token trocado "
                    + "para aquele destino. Se a tela nao precisa de composicao, encaminhar e resposta "
                    + "legitima do BFF (ADR-0013).")
    @SecurityRequirement(name = "cookieSessao")
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Contas do Cliente. Corpo JSON opaco de fk-servico-carteira-clientes, "
                            + "repassado sem tipagem no BFF.",
                    content = @Content(schema = @Schema(type = "object", description = "Corpo cru repassado sem desserializacao no BFF."))),
            @ApiResponse(responseCode = "400",
                    description = "clienteId fora do formato esperado. codigo = IDENTIFICADOR_INVALIDO.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = GlobalExceptionHandler.EnvelopeErroPublico.class))),
            @ApiResponse(responseCode = "401", description = "Sem sessao autenticada.", content = @Content),
            @ApiResponse(responseCode = "403",
                    description = "Sem direito de atendimento atual segundo o servico dono do recurso. Status "
                            + "preservado so quando o corpo upstream carrega um codigo publicado na allow-list "
                            + "deste BFF; hoje fk-servico-carteira-clientes ainda nao publica codigo.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = GlobalExceptionHandler.EnvelopeErroPublico.class))),
            @ApiResponse(responseCode = "502",
                    description = "Resposta do servico dono do recurso nao reconhecida pela allow-list deste BFF.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = GlobalExceptionHandler.EnvelopeErroPublico.class))),
            @ApiResponse(responseCode = "503",
                    description = "fk-servico-carteira-clientes indisponivel ou inalcancavel no momento.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = GlobalExceptionHandler.EnvelopeErroPublico.class))),
    })
    @GetMapping(path = "/api/clientes/{clienteId}/contas", produces = "application/json")
    String listarContasDoCliente(
            @Parameter(description = "Identificador do Cliente selecionado.", example = "1",
                    schema = @Schema(pattern = "^[0-9]{1,10}$"))
            @PathVariable String clienteId,
            @Parameter(hidden = true) Authentication authentication,
            @Parameter(hidden = true) HttpServletRequest request,
            @Parameter(hidden = true) HttpServletResponse response) {

        IdentificadorHost.validar(clienteId, "clienteId");

        // Encaminhamento simples: a tela precisa das contas, e nao ha nada a compor ainda. Se a
        // tela nao precisa de composicao, encaminhar e resposta legitima (ADR-0013).
        return carteiraClientesRestClient.get()
                .uri("/clientes/{clienteId}/contas", clienteId)
                .header("Authorization", "Bearer " + tokenCarteira(authentication, request, response))
                .retrieve()
                .body(String.class);
    }

    @Operation(
            operationId = "consultarAtendimento",
            summary = "Modelo de apresentacao da tela de atendimento -- Cliente, ContaCorrente e limite",
            description = "Composto pelo BFF a partir de fk-servico-carteira-clientes (identidade e vinculo) e "
                    + "fk-servico-credito (limite vigente) -- nenhum dos dois contextos precisa conhecer dado "
                    + "que nao e seu (AC30, ADR-0013). Resultado e modelo de apresentacao, nao agregado. Cada "
                    + "servico verifica o direito de atendimento por conta propria; o BFF nao e enforcement "
                    + "point unico e nao suaviza a recusa de quem e dono do recurso (ADR-0007).")
    @SecurityRequirement(name = "cookieSessao")
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Cliente, ContaCorrente e LimiteChequeEspecialVigente numa unica resposta.",
                    content = @Content(schema = @Schema(implementation = AtendimentoResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "clienteId ou contaId fora do formato esperado. codigo = IDENTIFICADOR_INVALIDO.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = GlobalExceptionHandler.EnvelopeErroPublico.class))),
            @ApiResponse(responseCode = "401", description = "Sem sessao autenticada.", content = @Content),
            @ApiResponse(responseCode = "403",
                    description = "Sem direito de atendimento atual. Status preservado so quando o corpo "
                            + "upstream carrega um codigo da allow-list deste BFF.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = GlobalExceptionHandler.EnvelopeErroPublico.class))),
            @ApiResponse(responseCode = "404",
                    description = "A conta nao pertence aquele Cliente, ou nao e reconhecida pelo CoreLegado.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = GlobalExceptionHandler.EnvelopeErroPublico.class))),
            @ApiResponse(responseCode = "502",
                    description = "Resposta de um Resource Server nao reconhecida pela allow-list deste BFF.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = GlobalExceptionHandler.EnvelopeErroPublico.class))),
            @ApiResponse(responseCode = "503",
                    description = "Um dos servicos esta indisponivel. Mensagem unica de negocio: o gerente nao "
                            + "precisa saber qual dependencia caiu.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = GlobalExceptionHandler.EnvelopeErroPublico.class))),
    })
    @GetMapping(path = "/api/clientes/{clienteId}/contas/{contaId}/atendimento", produces = "application/json")
    AtendimentoResponse atendimento(
            @Parameter(description = "Identificador do Cliente selecionado.", example = "1",
                    schema = @Schema(pattern = "^[0-9]{1,10}$"))
            @PathVariable String clienteId,
            @Parameter(description = "Identificador da ContaCorrente.", example = "10001",
                    schema = @Schema(pattern = "^[0-9]{1,10}$"))
            @PathVariable String contaId,
            @Parameter(hidden = true) Authentication authentication,
            @Parameter(hidden = true) HttpServletRequest request,
            @Parameter(hidden = true) HttpServletResponse response) {

        IdentificadorHost.validar(clienteId, "clienteId");
        IdentificadorHost.validar(contaId, "contaId");

        ContextoAtendimentoResponse contexto = carteiraClientesRestClient.get()
                .uri("/clientes/{clienteId}/contas/{contaId}/contexto-atendimento", clienteId, contaId)
                .header("Authorization", "Bearer " + tokenCarteira(authentication, request, response))
                .retrieve()
                .body(ContextoAtendimentoResponse.class);

        LimiteChequeEspecialVigenteResponse limite = creditoRestClient.get()
                .uri("/clientes/{clienteId}/contas/{contaId}/limite-cheque-especial-vigente", clienteId, contaId)
                .header("Authorization", "Bearer " + tokenCredito(authentication, request, response))
                .retrieve()
                .body(LimiteChequeEspecialVigenteResponse.class);

        return AtendimentoResponse.de(contexto, limite);
    }

    private String tokenCarteira(Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
        return tokenResolver.tokenPara(
                TokenExchangeConfig.REGISTRATION_CARTEIRA_CLIENTES, authentication, request, response);
    }

    private String tokenCredito(Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
        return tokenResolver.tokenPara(
                TokenExchangeConfig.REGISTRATION_CREDITO_LEITURA, authentication, request, response);
    }
}
