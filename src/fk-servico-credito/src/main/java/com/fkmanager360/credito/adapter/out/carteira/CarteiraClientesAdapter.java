package com.fkmanager360.credito.adapter.out.carteira;

import com.fkmanager360.credito.application.port.out.CarteiraClientesUnavailableException;
import com.fkmanager360.credito.application.port.out.ContaNaoEncontradaException;
import com.fkmanager360.credito.application.port.out.DireitoDeAtendimentoAusenteException;
import com.fkmanager360.credito.application.port.out.DireitoDeAtendimentoPort;
import com.fkmanager360.credito.config.TokenExchangeConfig;
import com.fkmanager360.credito.domain.ClienteId;
import com.fkmanager360.credito.domain.ContaId;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Adapter de saida para CarteiraClientes, que e a autoridade sobre o direito de atendimento
 * (ADR-0007). Credito nao reimplementa essa regra: pergunta a quem e dono, apresentando um token
 * obtido por Token Exchange com {@code aud = servico-carteira-clientes} (ADR-0015, AC21).
 *
 * <p>O 403 remoto atravessa como {@link DireitoDeAtendimentoAusenteException} e interrompe o
 * caso de uso antes de qualquer leitura no Core -- e assim que "nenhuma chamada ao CoreLegado e
 * emitida" (AC23) deixa de depender de disciplina e passa a ser consequencia estrutural.
 */
@Component
public class CarteiraClientesAdapter implements DireitoDeAtendimentoPort {

    private final RestClient restClient;
    private final OAuth2AuthorizedClientManager authorizedClientManager;

    public CarteiraClientesAdapter(
            RestClient carteiraClientesRestClient, OAuth2AuthorizedClientManager authorizedClientManager) {
        this.restClient = carteiraClientesRestClient;
        this.authorizedClientManager = authorizedClientManager;
    }

    @Override
    public ClienteId confirmarDireitoDeAtendimento(ClienteId clienteId, ContaId contaId) {
        ContextoAtendimentoResponse resposta;
        try {
            resposta = restClient.get()
                    .uri("/clientes/{clienteId}/contas/{contaId}/contexto-atendimento",
                            clienteId.valor(), contaId.valor())
                    .header("Authorization", "Bearer " + tokenDelegado())
                    .retrieve()
                    .body(ContextoAtendimentoResponse.class);
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
                    "Falha ao consultar o contexto de atendimento em CarteiraClientes", e);
        }

        if (resposta == null || resposta.clienteId() == null) {
            throw new CarteiraClientesUnavailableException(
                    "CarteiraClientes devolveu contexto de atendimento sem clienteId", null);
        }

        return new ClienteId(resposta.clienteId());
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
