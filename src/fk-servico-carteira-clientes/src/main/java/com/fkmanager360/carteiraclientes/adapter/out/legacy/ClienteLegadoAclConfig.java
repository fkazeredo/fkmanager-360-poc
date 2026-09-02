package com.fkmanager360.carteiraclientes.adapter.out.legacy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Cliente HTTP desta ACL, com timeouts curtos e explicitos -- uma consulta de dados mestres que
 * trava indefinidamente travaria a listagem inteira da carteira.
 */
@Configuration
public class ClienteLegadoAclConfig {

    @Bean
    RestClient clienteLegadoRestClient(
            @Value("${carteira-clientes.core-legado.base-url}") String baseUrl,
            @Value("${carteira-clientes.core-legado.connect-timeout:PT2S}") Duration connectTimeout,
            @Value("${carteira-clientes.core-legado.read-timeout:PT3S}") Duration readTimeout) {

        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }
}
