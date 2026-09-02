package com.fkmanager360.bffgerente.adapter.in.web;

import com.fkmanager360.bffgerente.config.DelegatedTokenResolver;
import com.fkmanager360.bffgerente.config.TokenExchangeConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
public class CarteiraProxyController {

    private final RestClient carteiraClientesRestClient;
    private final DelegatedTokenResolver tokenResolver;

    public CarteiraProxyController(RestClient carteiraClientesRestClient, DelegatedTokenResolver tokenResolver) {
        this.carteiraClientesRestClient = carteiraClientesRestClient;
        this.tokenResolver = tokenResolver;
    }

    @GetMapping(path = "/api/carteira/clientes", produces = "application/json")
    String listarClientesDaCarteira(
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response,
            // name= explicito: o parametro de query publico ("pagina"/"tamanho") e contrato HTTP
            // ja exercitado por Angular/Playwright/curl -- so o identificador Java interno segue
            // a convencao tecnica em ingles.
            @RequestParam(name = "pagina", defaultValue = "0") int page,
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
