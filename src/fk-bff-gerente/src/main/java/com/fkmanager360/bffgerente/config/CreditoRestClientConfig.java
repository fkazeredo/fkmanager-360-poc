package com.fkmanager360.bffgerente.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Orcamento de timeout documentado em {@link TokenExchangeConfig} (achado I9 do review de #0002).
 * O pior caso DECLARADO de servico-credito e a soma de tres chamadas sequenciais que ele mesmo
 * faz -- Token Exchange (5s) + CarteiraClientes, endpoint estreito (5s) + CoreLegado direto (5s)
 * = 15s -- e este cliente precisa exceder isso com margem: 15s + 3s = 18s. O timeout anterior
 * (8s) era exatamente a soma dos DOIS ultimos passos, sem contar o primeiro nem a margem --
 * suficiente para o BFF desistir antes de servico-credito, que ele mesmo acionou, ter chance de
 * concluir dentro do que promete.
 */
@Configuration
public class CreditoRestClientConfig {

    @Bean
    RestClient creditoRestClient(
            @Value("${bff-gerente.credito.base-url}") String baseUrl,
            @Value("${bff-gerente.credito.connect-timeout:PT2S}") Duration connectTimeout,
            @Value("${bff-gerente.credito.read-timeout:PT18S}") Duration readTimeout) {

        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }
}
