package com.fkmanager360.carteiraclientes.adapter.out.legacy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Cliente HTTP da ACL deste contexto sobre o CoreLegado, compartilhado pelos adapters que a
 * compoem (dados mestres do Cliente e contas correntes) -- mesmo host, mesmo contrato, mesma
 * politica de timeout. Timeouts curtos e explicitos: uma consulta que trava indefinidamente
 * travaria a tela inteira do gerente.
 */
@Configuration
public class CoreLegadoAclConfig {

    @Bean
    RestClient coreLegadoRestClient(
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
