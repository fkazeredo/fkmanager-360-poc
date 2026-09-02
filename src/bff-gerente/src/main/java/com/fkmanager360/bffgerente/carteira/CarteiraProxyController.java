package com.fkmanager360.bffgerente.carteira;

import com.fkmanager360.bffgerente.seguranca.TokenExchangeConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * O bff-gerente compoe a tela de atendimento a partir de servico-carteira-clientes (ADR-0013).
 * Neste ticket, so um contexto existe -- a composicao com servico-credito (#0002) e o que fara
 * disto de fato um modelo de apresentacao combinado. O BFF nunca fala com
 * simulador-core-legado (AC30) e nunca substitui a autorizacao de recurso feita pelo servico
 * dono do recurso (ADR-0007): o 403 de CarteiraClientes so atravessa.
 */
@RestController
public class CarteiraProxyController {

    private final RestClient carteiraClientesRestClient;
    private final OAuth2AuthorizedClientManager authorizedClientManager;

    public CarteiraProxyController(RestClient carteiraClientesRestClient, OAuth2AuthorizedClientManager authorizedClientManager) {
        this.carteiraClientesRestClient = carteiraClientesRestClient;
        this.authorizedClientManager = authorizedClientManager;
    }

    @GetMapping("/api/carteira/clientes")
    String listarClientesDaCarteira(
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response,
            @RequestParam(defaultValue = "0") int pagina,
            @RequestParam(required = false) Integer tamanho) {

        String tokenDelegado = obterTokenDelegado(authentication, request, response);

        return carteiraClientesRestClient.get()
                .uri(uriBuilder -> {
                    uriBuilder.path("/carteira/clientes").queryParam("pagina", pagina);
                    if (tamanho != null) {
                        uriBuilder.queryParam("tamanho", tamanho);
                    }
                    return uriBuilder.build();
                })
                .header("Authorization", "Bearer " + tokenDelegado)
                .retrieve()
                .body(String.class);
    }

    private String obterTokenDelegado(Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
        OAuth2AuthorizeRequest authorizeRequest = OAuth2AuthorizeRequest
                .withClientRegistrationId(TokenExchangeConfig.REGISTRATION_CARTEIRA_CLIENTES)
                .principal(authentication)
                .attribute(HttpServletRequest.class.getName(), request)
                .attribute(HttpServletResponse.class.getName(), response)
                .build();

        OAuth2AuthorizedClient authorizedClient = authorizedClientManager.authorize(authorizeRequest);
        if (authorizedClient == null) {
            throw new IllegalStateException("Nao foi possivel obter token delegado para servico-carteira-clientes");
        }
        return authorizedClient.getAccessToken().getTokenValue();
    }

    @RestControllerAdvice(assignableTypes = CarteiraProxyController.class)
    static class TratamentoDeErros {

        @ExceptionHandler(HttpClientErrorException.Forbidden.class)
        ProblemDetail semDireitoDeAtendimento(HttpClientErrorException.Forbidden e) {
            return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Sem direito de atendimento atual");
        }

        @ExceptionHandler({HttpServerErrorException.class, ResourceAccessException.class})
        ProblemDetail carteiraClientesIndisponivel(Exception e) {
            return ProblemDetail.forStatusAndDetail(
                    HttpStatus.SERVICE_UNAVAILABLE, "Nao foi possivel concluir a operacao agora, tente novamente");
        }
    }
}
