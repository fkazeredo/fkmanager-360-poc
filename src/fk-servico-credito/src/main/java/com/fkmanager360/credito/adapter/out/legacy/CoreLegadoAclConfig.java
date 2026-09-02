package com.fkmanager360.credito.adapter.out.legacy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Cliente HTTP da ACL propria de Credito sobre o CoreLegado (ADR-0004), com timeouts curtos e
 * explicitos: a leitura do limite esta no caminho sincrono do atendimento, e uma consulta que
 * trava indefinidamente travaria a tela do gerente.
 */
@Configuration
public class CoreLegadoAclConfig {

    @Bean
    RestClient coreLegadoRestClient(
            @Value("${credito.core-legado.base-url}") String baseUrl,
            @Value("${credito.core-legado.connect-timeout:PT2S}") Duration connectTimeout,
            @Value("${credito.core-legado.read-timeout:PT3S}") Duration readTimeout) {

        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }
}
