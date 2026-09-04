package com.fkmanager360.simuladorcorelegado.adapter.out.callback;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@code CallbackDispatcher} (#0005) contra receiver e token endpoint reais (WireMock), mesmo
 * cliente HTTP de producao. Prova o payload e o Bearer entregues, o no-op quando desabilitado, e
 * que falha (token ou entrega) nunca escapa como excecao -- este e o limite exato da garantia:
 * UMA tentativa por processamento, nunca reenviada por conta propria (recuperacao e do
 * reconciliador, #0006).
 */
class CallbackDispatcherTest {

    private static final String CALLBACK_PATH = "/callbacks/efetivacoes";
    private static final String TOKEN_PATH = "/oauth2/token";

    private WireMockServer servidor;
    private RestClient restClient;

    @BeforeEach
    void subirServidor() {
        servidor = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        servidor.start();

        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(2));
        restClient = RestClient.builder().requestFactory(requestFactory).build();

        servidor.stubFor(post(urlEqualTo(TOKEN_PATH)).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("{\"access_token\":\"tok-callback\",\"token_type\":\"Bearer\",\"expires_in\":300}")));
    }

    @AfterEach
    void pararServidor() {
        servidor.stop();
    }

    private String urlDe(String path) {
        return "http://localhost:" + servidor.port() + path;
    }

    private CallbackDispatcher dispatcherHabilitado() {
        TokenClienteCredentials tokenClienteCredentials =
                new TokenClienteCredentials(restClient, urlDe(TOKEN_PATH), "simulador-core-legado", "segredo", "credito.callback");
        return new CallbackDispatcher(restClient, tokenClienteCredentials, urlDe(CALLBACK_PATH));
    }

    @Test
    void enviarConfirmacao_entregaOPayloadDeSucessoComBearer() {
        servidor.stubFor(post(urlEqualTo(CALLBACK_PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"resultado\":\"PROCESSADO\"}")));

        dispatcherHabilitado().enviarConfirmacao(new ConfirmacaoEfetivacao("id-eft-1", "PRT-1", "000000000600000", "id-cor-1"));

        servidor.verify(postRequestedFor(urlEqualTo(CALLBACK_PATH))
                .withHeader("Authorization", equalTo("Bearer tok-callback"))
                .withRequestBody(matchingJsonPath("$.idEft", equalTo("id-eft-1")))
                .withRequestBody(matchingJsonPath("$.numPrt", equalTo("PRT-1")))
                .withRequestBody(matchingJsonPath("$.codRet", equalTo("000")))
                .withRequestBody(matchingJsonPath("$.vlrLimEft", equalTo("000000000600000")))
                .withRequestBody(matchingJsonPath("$.idCor", equalTo("id-cor-1"))));
    }

    @Test
    void enviarConfirmacao_urlVazia_naoChamaNadaENaoLanca() {
        CallbackDispatcher dispatcher = new CallbackDispatcher(restClient,
                new TokenClienteCredentials(restClient, urlDe(TOKEN_PATH), "cid", "csecret", "credito.callback"), "");

        assertThatCode(() -> dispatcher.enviarConfirmacao(new ConfirmacaoEfetivacao("id-eft-2", "PRT-2", "600000", "id-cor-2")))
                .doesNotThrowAnyException();

        servidor.verify(0, postRequestedFor(urlEqualTo(CALLBACK_PATH)));
        servidor.verify(0, postRequestedFor(urlEqualTo(TOKEN_PATH)));
    }

    @Test
    void enviarConfirmacao_receiverIndisponivel_naoLancaExcecao() {
        servidor.stubFor(post(urlEqualTo(CALLBACK_PATH)).willReturn(aResponse().withStatus(503)));

        assertThatCode(() -> dispatcherHabilitado().enviarConfirmacao(
                new ConfirmacaoEfetivacao("id-eft-3", "PRT-3", "600000", "id-cor-3")))
                .doesNotThrowAnyException();
    }

    @Test
    void enviarConfirmacao_falhaAoObterToken_naoChamaOReceiverENaoLanca() {
        servidor.stubFor(post(urlEqualTo(TOKEN_PATH)).willReturn(aResponse().withStatus(500)));
        servidor.stubFor(post(urlEqualTo(CALLBACK_PATH)).willReturn(aResponse().withStatus(200)));

        assertThatCode(() -> dispatcherHabilitado().enviarConfirmacao(
                new ConfirmacaoEfetivacao("id-eft-4", "PRT-4", "600000", "id-cor-4")))
                .doesNotThrowAnyException();

        servidor.verify(0, postRequestedFor(urlEqualTo(CALLBACK_PATH)));
    }
}
