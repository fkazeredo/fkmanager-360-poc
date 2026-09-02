package com.fkmanager360.bffgerente.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Orcamento de timeout documentado em {@link TokenExchangeConfig} (achado I9 do review de #0002).
 * O read timeout deste cliente precisa exceder o pior caso DECLARADO de CarteiraClientes -- 10s,
 * dominado por {@code /contexto-atendimento} (duas chamadas sequenciais ao CoreLegado) -- com
 * margem: 10s + 3s = 13s. O mesmo cliente atende tambem {@code /contas} (pior caso 5s), que cabe
 * com folga no mesmo timeout; um unico RestClient dimensionado para o uso mais caro e mais simples
 * do que dois clientes com timeouts diferentes para dois paths do mesmo servico.
 */
@Configuration
public class CarteiraClientesRestClientConfig {

    @Bean
    RestClient carteiraClientesRestClient(
            @Value("${bff-gerente.carteira-clientes.base-url}") String baseUrl,
            @Value("${bff-gerente.carteira-clientes.connect-timeout:PT2S}") Duration connectTimeout,
            @Value("${bff-gerente.carteira-clientes.read-timeout:PT13S}") Duration readTimeout) {

        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }
}
