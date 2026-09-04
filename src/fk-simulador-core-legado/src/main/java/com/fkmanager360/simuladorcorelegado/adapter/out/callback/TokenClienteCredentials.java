package com.fkmanager360.simuladorcorelegado.adapter.out.callback;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

/**
 * Cliente OAuth2 {@code client_credentials} minimo e isolado (#0005) -- deliberadamente sem
 * {@code spring-security-oauth2-client}, que arrastaria Spring Security para um simulador cujo
 * contrato e host-centric aberto por decisao ja registrada (ADR-0005). Se esta classe deixar de
 * ser pequena, o crescimento precisa ser reportado antes de continuar.
 *
 * <p><b>Cache thread-safe, inclusive no refresh.</b> A leitura do caminho feliz (token ainda
 * valido) e lock-free, sobre um campo {@code volatile}. So o refresh entra na secao critica, e
 * repete a checagem de validade DENTRO dela -- sem isso, duas threads que encontrem o token
 * expirado ao mesmo tempo disparariam duas requisicoes simultaneas ao token endpoint; com a
 * dupla checagem, a segunda thread a entrar na secao critica reaproveita o token que a primeira
 * acabou de obter.
 *
 * <p>Nunca loga o access token nem o client secret.
 */
@Component
class TokenClienteCredentials {

    private static final Duration MARGEM_DE_SEGURANCA = Duration.ofSeconds(10);
    private static final long EXPIRES_IN_PADRAO_SEGUNDOS = 60;

    private final RestClient tokenRestClient;
    private final String tokenUri;
    private final String clientId;
    private final String clientSecret;
    private final String scope;

    private final Object travaDeRefresh = new Object();
    private volatile TokenEmCache cache;

    TokenClienteCredentials(
            @Qualifier("callbackRestClient") RestClient tokenRestClient,
            @Value("${simulador.callback.auth.token-uri:}") String tokenUri,
            @Value("${simulador.callback.auth.client-id:}") String clientId,
            @Value("${simulador.callback.auth.client-secret:}") String clientSecret,
            @Value("${simulador.callback.auth.scope:credito.callback}") String scope) {
        this.tokenRestClient = tokenRestClient;
        this.tokenUri = tokenUri;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.scope = scope;
    }

    String obterToken() {
        TokenEmCache atual = cache;
        if (atual != null && atual.aindaValido(Instant.now())) {
            return atual.valor();
        }

        synchronized (travaDeRefresh) {
            atual = cache;
            if (atual != null && atual.aindaValido(Instant.now())) {
                // Outra thread ja renovou enquanto esperavamos a trava.
                return atual.valor();
            }
            TokenEmCache novo = requisitarToken();
            cache = novo;
            return novo.valor();
        }
    }

    private TokenEmCache requisitarToken() {
        String corpo = "grant_type=client_credentials&scope=" + URLEncoder.encode(scope, StandardCharsets.UTF_8);

        TokenResponse resposta = tokenRestClient.post()
                .uri(tokenUri)
                .headers(headers -> headers.setBasicAuth(clientId, clientSecret))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(corpo)
                .retrieve()
                .body(TokenResponse.class);

        if (resposta == null || resposta.accessToken() == null || resposta.accessToken().isBlank()) {
            throw new IllegalStateException("Token endpoint devolveu resposta sem access_token");
        }

        long expiresInSegundos = resposta.expiresIn() == null ? EXPIRES_IN_PADRAO_SEGUNDOS : resposta.expiresIn();
        Instant expiraEm = Instant.now().plusSeconds(expiresInSegundos).minus(MARGEM_DE_SEGURANCA);
        return new TokenEmCache(resposta.accessToken(), expiraEm);
    }

    private record TokenEmCache(String valor, Instant expiraEm) {
        boolean aindaValido(Instant agora) {
            return agora.isBefore(expiraEm);
        }
    }

    private record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") Long expiresIn) {
    }
}
