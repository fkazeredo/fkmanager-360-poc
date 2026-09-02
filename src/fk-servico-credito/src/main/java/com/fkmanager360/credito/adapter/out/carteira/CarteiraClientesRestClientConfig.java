package com.fkmanager360.credito.adapter.out.carteira;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Cliente HTTP para CarteiraClientes. Timeouts curtos pelo mesmo motivo da ACL do Core: esta
 * chamada esta no caminho sincrono do atendimento, e ela e a primeira -- se travar, nada mais
 * acontece.
 */
@Configuration
public class CarteiraClientesRestClientConfig {

    @Bean
    RestClient carteiraClientesRestClient(
            @Value("${credito.carteira-clientes.base-url}") String baseUrl,
            @Value("${credito.carteira-clientes.connect-timeout:PT2S}") Duration connectTimeout,
            @Value("${credito.carteira-clientes.read-timeout:PT5S}") Duration readTimeout) {

        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }
}
