package com.fkmanager360.simuladorcorelegado.adapter.out.callback;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Dispara a confirmacao autoritativa de uma efetivacao para o {@code servico-credito} (spec,
 * secao "Callback"; ticket #0005).
 *
 * <p><b>Semantica precisa -- nao chamar isto de entrega at-least-once.</b> Este simulador emite
 * UMA tentativa assincrona de callback por processamento, sem segundo subsistema de retry. Quem e
 * preparado para redelivery/entrega at-least-once e o ENDPOINT de destino (idempotente por
 * construcao -- {@code CallbackEfetivacaoController} do servico-credito). Falha ou perda desta
 * tentativa fica para a recuperacao pelo reconciliador de #0006, nunca reenviada por conta
 * propria a partir daqui. Falha na obtencao do token client_credentials ABORTA a tentativa --
 * nunca envia sem Bearer.
 *
 * <p>Desabilitado quando {@code simulador.callback.url} esta vazio -- os perfis standalone
 * (sem {@code servico-credito} de pe) e os {@code @WebMvcTest} existentes continuam sem nenhum
 * efeito colateral de rede.
 */
@Component
@Slf4j
public class CallbackDispatcher {

    private final RestClient callbackRestClient;
    private final TokenClienteCredentials tokenClienteCredentials;
    private final String callbackUrl;

    public CallbackDispatcher(
            @Qualifier("callbackRestClient") RestClient callbackRestClient,
            TokenClienteCredentials tokenClienteCredentials,
            @Value("${simulador.callback.url:}") String callbackUrl) {
        this.callbackRestClient = callbackRestClient;
        this.tokenClienteCredentials = tokenClienteCredentials;
        this.callbackUrl = callbackUrl;
    }

    public void enviarConfirmacao(ConfirmacaoEfetivacao confirmacao) {
        if (callbackUrl == null || callbackUrl.isBlank()) {
            log.debug("Callback desabilitado (simulador.callback.url vazio) -- efetivacao idEft={} "
                    + "processada sem notificar", confirmacao.idEft());
            return;
        }

        String token;
        try {
            token = tokenClienteCredentials.obterToken();
        } catch (RuntimeException e) {
            log.error("Falha ao obter token client_credentials para o callback de idEft={} -- "
                    + "tentativa abortada, nunca enviado sem Bearer", confirmacao.idEft(), e);
            return;
        }

        try {
            callbackRestClient.post()
                    .uri(callbackUrl)
                    .headers(headers -> headers.setBearerAuth(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(CallbackPayload.deSucesso(confirmacao))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            log.error("Falha ao entregar callback de idEft={} -- uma unica tentativa por "
                    + "processamento; recuperacao pertence ao reconciliador (#0006)", confirmacao.idEft(), e);
        }
    }
}
