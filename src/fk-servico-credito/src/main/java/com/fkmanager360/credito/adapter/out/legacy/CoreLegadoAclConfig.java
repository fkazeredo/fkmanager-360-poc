package com.fkmanager360.credito.adapter.out.legacy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Clientes HTTP da ACL propria de Credito sobre o CoreLegado (ADR-0004). Tres beans, tres
 * orcamentos de timeout deliberadamente distintos -- nao compartilhados -- porque as tres
 * chamadas tem SLAs diferentes:
 *
 * <ul>
 *     <li>{@link #coreLegadoRestClient}: leitura do limite, caminho SINCRONO do atendimento --
 *     uma consulta que trava indefinidamente travaria a tela do gerente, entao os timeouts sao
 *     curtos de proposito;
 *     <li>{@link #efetivacaoLegadoRestClient}: instrucao de efetivacao, chamada pelo dispatcher em
 *     background (plano #0004) -- nenhum usuario espera esta resposta na tela, entao um timeout
 *     tao curto quanto o da leitura sincrona so faria um Core lento-mas-saudavel ser classificado
 *     {@code FalhaTransitoria} e queimar tentativas do orcamento de retry (max 4) por uma latencia
 *     perfeitamente aceitavel para um job de fundo;
 *     <li>{@link #reconciliacaoLegadoRestClient}: consulta de status pelo reconciliador em
 *     background (#0006) -- mesma logica do dispatcher: sem usuario esperando, e sem orcamento de
 *     retry finito para queimar (o reconciliador reagenda dentro da janela, nunca conta
 *     "tentativas maximas").
 * </ul>
 */
@Configuration
public class CoreLegadoAclConfig {

    @Bean
    RestClient coreLegadoRestClient(
            @Value("${credito.core-legado.base-url}") String baseUrl,
            @Value("${credito.core-legado.connect-timeout:PT2S}") Duration connectTimeout,
            @Value("${credito.core-legado.read-timeout:PT3S}") Duration readTimeout) {
        return construirRestClient(baseUrl, connectTimeout, readTimeout);
    }

    @Bean
    RestClient efetivacaoLegadoRestClient(
            @Value("${credito.core-legado.base-url}") String baseUrl,
            @Value("${credito.efetivacao.entrega.core.connect-timeout:PT2S}") Duration connectTimeout,
            @Value("${credito.efetivacao.entrega.core.read-timeout:PT5S}") Duration readTimeout) {
        return construirRestClient(baseUrl, connectTimeout, readTimeout);
    }

    @Bean
    RestClient reconciliacaoLegadoRestClient(
            @Value("${credito.core-legado.base-url}") String baseUrl,
            @Value("${credito.efetivacao.reconciliacao.core.connect-timeout:PT2S}") Duration connectTimeout,
            @Value("${credito.efetivacao.reconciliacao.core.read-timeout:PT5S}") Duration readTimeout) {
        return construirRestClient(baseUrl, connectTimeout, readTimeout);
    }

    private static RestClient construirRestClient(String baseUrl, Duration connectTimeout, Duration readTimeout) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }
}
