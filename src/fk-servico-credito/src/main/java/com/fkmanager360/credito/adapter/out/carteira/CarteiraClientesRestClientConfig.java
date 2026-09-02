package com.fkmanager360.credito.adapter.out.carteira;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Cliente HTTP para CarteiraClientes -- a chamada de autorizacao que precede qualquer leitura no
 * Core (AC23). Orcamento de timeout (achado I9 do review de #0002): o endpoint estreito que este
 * cliente consome ({@code /direito-de-atendimento}) tem pior caso DECLARADO de 5s em
 * CarteiraClientes (uma chamada ao CoreLegado); este cliente precisa exceder isso com margem:
 * 5s + 3s = 8s. O valor anterior (5s) era exatamente igual ao pior caso do lado de la, sem
 * margem alguma -- suficiente para este cliente desistir no exato instante em que CarteiraClientes
 * estava prestes a responder.
 */
@Configuration
public class CarteiraClientesRestClientConfig {

    @Bean
    RestClient carteiraClientesRestClient(
            @Value("${credito.carteira-clientes.base-url}") String baseUrl,
            @Value("${credito.carteira-clientes.connect-timeout:PT2S}") Duration connectTimeout,
            @Value("${credito.carteira-clientes.read-timeout:PT8S}") Duration readTimeout) {

        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }
}
