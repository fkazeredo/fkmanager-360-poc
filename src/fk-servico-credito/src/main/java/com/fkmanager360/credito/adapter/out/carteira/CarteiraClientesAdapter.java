package com.fkmanager360.credito.adapter.out.carteira;

import com.fkmanager360.credito.application.port.out.CarteiraClientesUnavailableException;
import com.fkmanager360.credito.application.port.out.ContaNaoEncontradaException;
import com.fkmanager360.credito.application.port.out.DireitoDeAtendimentoAusenteException;
import com.fkmanager360.credito.application.port.out.DireitoDeAtendimentoPort;
import com.fkmanager360.credito.config.TokenExchangeConfig;
import com.fkmanager360.credito.domain.ClienteId;
import com.fkmanager360.credito.domain.ContaId;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Adapter de saida para CarteiraClientes, que e a autoridade sobre o direito de atendimento
 * (ADR-0007). Credito nao reimplementa essa regra: pergunta a quem e dono, apresentando um token
 * obtido por Token Exchange com {@code aud = servico-carteira-clientes} (ADR-0015, AC21).
 *
 * <p>Chama a operacao <b>estreita</b> {@code /direito-de-atendimento} -- 204 sem corpo --, e nao
 * o contexto rico que o bff-gerente usa para compor a tela. Duas consequencias diretas: nao ha
 * corpo de resposta a desserializar, e portanto nenhuma classe de defeito por
 * {@code clienteId} malformado devolvido pelo peer pode existir aqui; e uma falha na consulta de
 * dados mestres do Cliente dentro de CarteiraClientes -- que a operacao estreita nunca invoca --
 * nao pode mais derrubar a leitura do limite (achados I4 e I6 do review de #0002).
 *
 * <p>O 403 remoto atravessa como {@link DireitoDeAtendimentoAusenteException} e interrompe o
 * caso de uso antes de qualquer leitura no Core -- e assim que "nenhuma chamada ao CoreLegado e
 * emitida" (AC23) deixa de depender de disciplina e passa a ser consequencia estrutural.
 */
@Component
@RequiredArgsConstructor
public class CarteiraClientesAdapter implements DireitoDeAtendimentoPort {

    // Qualifier explicito (nao havia antes): o construtor manual dependia do NOME do parametro
    // (carteiraClientesRestClient) coincidir com o nome do @Bean para o Spring resolver por
    // convencao, ja que ha mais de um RestClient neste modulo (ver CoreLegadoAclConfig). O
    // parametro gerado por @RequiredArgsConstructor usa o nome do CAMPO (restClient), o que
    // quebraria essa resolucao implicita -- por isso o @Qualifier passa a ser explicito aqui.
    @Qualifier("carteiraClientesRestClient")
    private final RestClient restClient;

    private final OAuth2AuthorizedClientManager authorizedClientManager;

    @Override
    public void confirmarDireitoDeAtendimento(ClienteId clienteId, ContaId contaId) {
        String token;
        try {
            token = tokenDelegado();
        } catch (OAuth2AuthorizationException e) {
            // O servidor-autorizacao recusou a troca (allow-list, scope amplificado, AS fora do
            // ar) -- falha de integracao da cadeia, nao resposta de negocio sobre o atendimento.
            // Sem isto, OAuth2AuthorizationException nao e RestClientException e escaparia sem
            // handler ate o 500 generico.
            throw new CarteiraClientesUnavailableException(
                    "Falha ao obter token delegado para servico-carteira-clientes", e);
        }

        try {
            restClient.get()
                    .uri("/clientes/{clienteId}/contas/{contaId}/direito-de-atendimento",
                            clienteId.valor(), contaId.valor())
                    .header("Authorization", "Bearer " + token)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException.Forbidden e) {
            throw new DireitoDeAtendimentoAusenteException(
                    "CarteiraClientes recusou o atendimento da conta " + contaId.valor());
        } catch (HttpClientErrorException.NotFound e) {
            throw new ContaNaoEncontradaException(
                    "A conta " + contaId.valor() + " nao pertence ao Cliente " + clienteId.valor());
        } catch (RestClientException e) {
            // Qualquer outra coisa -- 5xx, timeout, reset, resposta ilegivel -- e indisponibilidade
            // da dependencia, nao resposta de negocio. Nao se conclui nada sobre o direito de
            // atendimento a partir de uma falha de comunicacao.
            throw new CarteiraClientesUnavailableException(
                    "Falha ao confirmar o direito de atendimento em CarteiraClientes", e);
        }
    }

    private String tokenDelegado() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            throw new IllegalStateException(
                    "Token Exchange exige uma operacao em nome de usuario autenticado -- nao ha principal no contexto");
        }

        OAuth2AuthorizeRequest authorizeRequest = OAuth2AuthorizeRequest
                .withClientRegistrationId(TokenExchangeConfig.REGISTRATION_CARTEIRA_CLIENTES)
                .principal(authentication)
                .build();

        OAuth2AuthorizedClient authorizedClient = authorizedClientManager.authorize(authorizeRequest);
        if (authorizedClient == null) {
            throw new IllegalStateException(
                    "Nao foi possivel obter token delegado para servico-carteira-clientes");
        }
        return authorizedClient.getAccessToken().getTokenValue();
    }
}
