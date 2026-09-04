package com.fkmanager360.simuladorcorelegado.adapter.out.callback;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Cliente HTTP unico para o callback e para o token endpoint (#0005) -- os dois destinos sao
 * chamados por URI absoluta (nunca relativa a um base-url), entao um unico {@link RestClient}
 * atende ambos. Timeouts curtos: fire-and-forget em background, nenhum usuario espera esta
 * resposta.
 */
@Configuration
class CallbackAclConfig {

    @Bean
    RestClient callbackRestClient(
            @Value("${simulador.callback.connect-timeout:PT2S}") Duration connectTimeout,
            @Value("${simulador.callback.read-timeout:PT3S}") Duration readTimeout) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);

        return RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }
}
