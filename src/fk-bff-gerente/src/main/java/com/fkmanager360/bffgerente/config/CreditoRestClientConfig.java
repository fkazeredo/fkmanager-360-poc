package com.fkmanager360.bffgerente.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class CreditoRestClientConfig {

    /**
     * Read timeout mais folgado que o de CarteiraClientes: servico-credito faz duas chamadas
     * remotas antes de responder (autorizacao e depois o Core), e o BFF nao pode desistir antes
     * de a cadeia que ele mesmo iniciou ter chance de terminar.
     */
    @Bean
    RestClient creditoRestClient(@Value("${bff-gerente.credito.base-url}") String baseUrl) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(8));

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }
}
