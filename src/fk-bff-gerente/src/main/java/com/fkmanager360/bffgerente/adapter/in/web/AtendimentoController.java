package com.fkmanager360.bffgerente.adapter.in.web;

import com.fkmanager360.bffgerente.config.DelegatedTokenResolver;
import com.fkmanager360.bffgerente.config.TokenExchangeConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
 */
@RestController
public class AtendimentoController {

    private final RestClient carteiraClientesRestClient;
    private final RestClient creditoRestClient;
    private final DelegatedTokenResolver tokenResolver;

    public AtendimentoController(
            RestClient carteiraClientesRestClient,
            RestClient creditoRestClient,
            DelegatedTokenResolver tokenResolver) {
        this.carteiraClientesRestClient = carteiraClientesRestClient;
        this.creditoRestClient = creditoRestClient;
        this.tokenResolver = tokenResolver;
    }

    @GetMapping(path = "/api/clientes/{clienteId}/contas", produces = "application/json")
    String listarContasDoCliente(
            @PathVariable String clienteId,
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response) {

        // Encaminhamento simples: a tela precisa das contas, e nao ha nada a compor ainda. Se a
        // tela nao precisa de composicao, encaminhar e resposta legitima (ADR-0013).
        return carteiraClientesRestClient.get()
                .uri("/clientes/{clienteId}/contas", clienteId)
                .header("Authorization", "Bearer " + tokenCarteira(authentication, request, response))
                .retrieve()
                .body(String.class);
    }

    @GetMapping(path = "/api/clientes/{clienteId}/contas/{contaId}/atendimento", produces = "application/json")
    AtendimentoResponse atendimento(
            @PathVariable String clienteId,
            @PathVariable String contaId,
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response) {

        ContextoAtendimentoResponse contexto = carteiraClientesRestClient.get()
                .uri("/clientes/{clienteId}/contas/{contaId}/contexto-atendimento", clienteId, contaId)
                .header("Authorization", "Bearer " + tokenCarteira(authentication, request, response))
                .retrieve()
                .body(ContextoAtendimentoResponse.class);

        LimiteVigenteResponse limite = creditoRestClient.get()
                .uri("/clientes/{clienteId}/contas/{contaId}/limite-cheque-especial-vigente", clienteId, contaId)
                .header("Authorization", "Bearer " + tokenCredito(authentication, request, response))
                .retrieve()
                .body(LimiteVigenteResponse.class);

        return AtendimentoResponse.de(contexto, limite);
    }

    private String tokenCarteira(Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
        return tokenResolver.tokenPara(
                TokenExchangeConfig.REGISTRATION_CARTEIRA_CLIENTES, authentication, request, response);
    }

    private String tokenCredito(Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
        return tokenResolver.tokenPara(
                TokenExchangeConfig.REGISTRATION_CREDITO, authentication, request, response);
    }
}
