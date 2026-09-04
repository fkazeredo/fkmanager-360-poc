package com.fkmanager360.simuladorcorelegado.adapter.out.callback;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.client.BasicCredentials;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cliente {@code client_credentials} minimo (#0005): contra um token endpoint real (WireMock),
 * mesmo cliente HTTP de producao. Prova o cache (nao requisita de novo enquanto valido), o
 * refresh (requisita de novo apos expirar) e a THREAD-SAFETY do refresh (N threads concorrentes
 * com o token expirado disparam UMA UNICA requisicao ao token endpoint -- guardrail do Owner).
 */
class TokenClienteCredentialsTest {

    private static final String TOKEN_PATH = "/oauth2/token";

    private WireMockServer tokenServer;
    private RestClient restClient;

    @BeforeEach
    void subirTokenServer() {
        tokenServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        tokenServer.start();

        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(2));
        restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    @AfterEach
    void pararTokenServer() {
        tokenServer.stop();
    }

    private String tokenUri() {
        return "http://localhost:" + tokenServer.port() + TOKEN_PATH;
    }

    private TokenClienteCredentials cliente(String clientId, String clientSecret) {
        return new TokenClienteCredentials(restClient, tokenUri(), clientId, clientSecret, "credito.callback");
    }

    private void stubToken(long expiresInSegundos) {
        tokenServer.stubFor(post(urlEqualTo(TOKEN_PATH)).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("{\"access_token\":\"tok-" + System.nanoTime() + "\",\"token_type\":\"Bearer\","
                        + "\"expires_in\":" + expiresInSegundos + "}")));
    }

    @Test
    void obterToken_primeiraChamada_requisitaOTokenEndpoint() {
        stubToken(300);

        String token = cliente("cid", "csecret").obterToken();

        assertThat(token).startsWith("tok-");
        tokenServer.verify(1, postRequestedFor(urlEqualTo(TOKEN_PATH)));
    }

    @Test
    void obterToken_apresentaBasicAuthComClientIdEClientSecret() {
        stubToken(300);

        cliente("meu-client", "meu-segredo").obterToken();

        tokenServer.verify(postRequestedFor(urlEqualTo(TOKEN_PATH))
                .withBasicAuth(new BasicCredentials("meu-client", "meu-segredo")));
    }

    @Test
    void obterToken_segundaChamadaComTokenAindaValido_reaproveitaOCache() {
        stubToken(300);
        TokenClienteCredentials cliente = cliente("cid", "csecret");

        String primeiro = cliente.obterToken();
        String segundo = cliente.obterToken();

        assertThat(segundo).isEqualTo(primeiro);
        tokenServer.verify(1, postRequestedFor(urlEqualTo(TOKEN_PATH)));
    }

    @Test
    void obterToken_tokenJaExpirado_requisitaNovamente() {
        // expires_in=0: a margem de seguranca (10s) subtraida faz o token cacheado nascer
        // sempre expirado -- toda chamada subsequente cai no caminho de refresh.
        stubToken(0);
        TokenClienteCredentials cliente = cliente("cid", "csecret");

        cliente.obterToken();
        cliente.obterToken();

        tokenServer.verify(2, postRequestedFor(urlEqualTo(TOKEN_PATH)));
    }

    @Test
    void obterToken_semAccessTokenNaResposta_lancaExcecaoSemCachear() {
        tokenServer.stubFor(post(urlEqualTo(TOKEN_PATH)).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json").withBody("{}")));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> cliente("cid", "csecret").obterToken());
    }

    @Test
    void obterToken_concorrenteComTokenSempreExpirado_requisitaUmaUnicaVez() throws Exception {
        // expires_in=0 (sempre expirado apos a margem): sem a dupla checagem sob a trava, N
        // threads concorrentes disparariam N requisicoes ao token endpoint.
        tokenServer.stubFor(post(urlEqualTo(TOKEN_PATH)).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json")
                .withFixedDelay(200)
                .withBody("{\"access_token\":\"tok-unico\",\"token_type\":\"Bearer\",\"expires_in\":300}")));
        TokenClienteCredentials cliente = cliente("cid", "csecret");

        int totalThreads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        try {
            CountDownLatch prontas = new CountDownLatch(totalThreads);
            CountDownLatch largada = new CountDownLatch(1);
            for (int i = 0; i < totalThreads; i++) {
                executor.submit(() -> {
                    prontas.countDown();
                    aguardar(largada);
                    cliente.obterToken();
                });
            }
            assertThat(prontas.await(5, TimeUnit.SECONDS)).isTrue();
            largada.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }

        tokenServer.verify(1, postRequestedFor(urlEqualTo(TOKEN_PATH)));
    }

    private static void aguardar(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
