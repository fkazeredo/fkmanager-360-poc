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

import java.util.regex.Pattern;

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
public class AtendimentoController {

    private static final Pattern IDENTIFICADOR_HOST = Pattern.compile("[0-9]{1,10}");

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

        validarIdentificador(clienteId, "clienteId");

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

        validarIdentificador(clienteId, "clienteId");
        validarIdentificador(contaId, "contaId");

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

    /**
     * Validacao na propria borda do BFF (achado I1 do review): um {@code clienteId}/{@code contaId}
     * fora do formato host nunca deveria virar uma chamada remota que so vai falhar la na frente
     * -- e sem esta validacao, o 400 que CarteiraClientes devolveria escapava do
     * {@link GlobalExceptionHandler} (que nao trata {@code HttpClientErrorException.BadRequest})
     * e virava 500 generico.
     */
    private static void validarIdentificador(String valor, String nomeDoCampo) {
        if (valor == null || !IDENTIFICADOR_HOST.matcher(valor).matches()) {
            throw new IllegalArgumentException(nomeDoCampo + " deve ser numerico, com ate 10 digitos: " + valor);
        }
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
