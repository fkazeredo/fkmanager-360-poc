package com.fkmanager360.servidorautorizacao.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;

import java.time.Duration;
import java.util.Set;

/**
 * Os clients da plataforma. Scopes sao capacidades grossas -- {@code carteira.leitura},
 * {@code credito.leitura}, {@code credito.escrita} -- e nunca politica de negocio: {@code
 * credito.aprovar-ate-50000} nao existe (ADR-0015).
 *
 * <p><b>A cadeia de delegacao, declarada por inteiro.</b> O gerente entra pelo bff-gerente com
 * {@code openid carteira.leitura credito.leitura credito.escrita}. O BFF troca por um token
 * DISTINTO por OPERACAO contra {@code servico-credito} -- least privilege por operacao (plano
 * #0003, secao 9): {@code aud = servico-credito} com {@code credito.leitura carteira.leitura}
 * para o GET do limite vigente, e {@code aud = servico-credito} com
 * {@code credito.escrita carteira.leitura} para o POST de submissao; e
 * {@code aud = servico-carteira-clientes} com {@code carteira.leitura} para a listagem/atendimento
 * da carteira. Ao continuar a operacao em nome do usuario, servico-credito troca <b>de novo</b>,
 * pedindo apenas {@code carteira.leitura} -- a segunda perna estreita capability em vez de ganhar
 * capability nova, e o registro abaixo torna isso estrutural: o unico scope que servico-credito
 * conhece e {@code carteira.leitura}, entao nem formular um pedido mais amplo e possivel. A
 * verificacao complementar, de que nada pedido excede o que o subject token ja tinha, esta em
 * {@link TokenExchangePolicyAuthenticationProvider}.
 */
@Configuration
public class RegisteredClientsConfig {

    static final String SCOPE_CARTEIRA_LEITURA = "carteira.leitura";
    static final String SCOPE_CREDITO_LEITURA = "credito.leitura";
    static final String SCOPE_CREDITO_ESCRITA = "credito.escrita";
    static final String SCOPE_CREDITO_CALLBACK = "credito.callback";

    static final String AUD_CARTEIRA_CLIENTES = "servico-carteira-clientes";
    static final String AUD_CREDITO = "servico-credito";

    /**
     * Client setting proprio de {@code client_credentials} (#0005): distinto de
     * {@link TokenExchangePolicyAuthenticationProvider#ALLOWED_TARGETS_SETTING} porque
     * {@code client_credentials} nao e uma troca -- o client so tem UM destino possivel, fixo, e
     * {@link TokenClaimsCustomizerConfig} o le para colocar {@code aud} no token emitido (sem
     * isto, o token sairia sem audience e seria recusado pelo {@code AudienceValidator} do
     * Resource Server -- so o ramo TOKEN_EXCHANGE do customizer preenchia {@code aud} ate aqui).
     */
    static final String CLIENT_CREDENTIALS_AUDIENCE_SETTING = "fk.client-credentials.audience";

    @Bean
    RegisteredClientRepository registeredClientRepository(
            PasswordEncoder passwordEncoder,
            @Value("${servidor-autorizacao.bff-client.client-id}") String bffClientId,
            @Value("${servidor-autorizacao.bff-client.client-secret}") String bffClientSecret,
            @Value("${servidor-autorizacao.bff-client.redirect-uri}") String redirectUri,
            @Value("${servidor-autorizacao.bff-client.post-logout-redirect-uri}") String postLogoutRedirectUri,
            @Value("${servidor-autorizacao.credito-client.client-id}") String creditoClientId,
            @Value("${servidor-autorizacao.credito-client.client-secret}") String creditoClientSecret,
            @Value("${servidor-autorizacao.simulador-client.client-id}") String simuladorClientId,
            @Value("${servidor-autorizacao.simulador-client.client-secret}") String simuladorClientSecret) {

        RegisteredClient bffGerente = RegisteredClient.withId("bff-gerente-client")
                .clientId(bffClientId)
                .clientSecret(passwordEncoder.encode(bffClientSecret))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .authorizationGrantType(AuthorizationGrantType.TOKEN_EXCHANGE)
                .redirectUri(redirectUri)
                .postLogoutRedirectUri(postLogoutRedirectUri)
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)
                .scope(SCOPE_CARTEIRA_LEITURA)
                .scope(SCOPE_CREDITO_LEITURA)
                .scope(SCOPE_CREDITO_ESCRITA)
                .clientSettings(ClientSettings.builder()
                        // PKCE obrigatorio: sem isto, o ticket nao terminou.
                        .requireProofKey(true)
                        .requireAuthorizationConsent(false)
                        // Allow-list de Token Exchange (ADR-0015): o BFF fala com os dois Resource
                        // Servers que a tela de atendimento compoe, e com mais nenhum.
                        .setting(TokenExchangePolicyAuthenticationProvider.ALLOWED_TARGETS_SETTING,
                                Set.of(AUD_CARTEIRA_CLIENTES, AUD_CREDITO))
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofMinutes(5))
                        .refreshTokenTimeToLive(Duration.ofHours(12))
                        .reuseRefreshTokens(false)
                        .build())
                .build();

        // Identidade de client propria: cada deployable que chama outro precisa da sua, para que
        // "quem chamou" seja sempre uma pergunta respondivel (ADR-0015).
        RegisteredClient servicoCredito = RegisteredClient.withId("servico-credito-client")
                .clientId(creditoClientId)
                .clientSecret(passwordEncoder.encode(creditoClientSecret))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                // Somente token-exchange: este client nunca inicia uma sessao de usuario nem age
                // por conta propria -- ele so continua uma operacao que ja chegou autenticada.
                .authorizationGrantType(AuthorizationGrantType.TOKEN_EXCHANGE)
                // Um unico scope registrado, e de proposito: nao ha como este client pedir
                // credito.leitura, credito.escrita ou qualquer outra coisa numa troca.
                .scope(SCOPE_CARTEIRA_LEITURA)
                .clientSettings(ClientSettings.builder()
                        .setting(TokenExchangePolicyAuthenticationProvider.ALLOWED_TARGETS_SETTING,
                                Set.of(AUD_CARTEIRA_CLIENTES))
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofMinutes(5))
                        .build())
                .build();

        // #0005: o CoreLegado confirma o resultado de uma efetivacao chamando de volta o
        // servico-credito, maquina-a-maquina. client_credentials, nunca token exchange -- este
        // client nao continua uma operacao de usuario, age em nome de si mesmo. Um unico scope,
        // de proposito: este client nao pode pedir credito.leitura, credito.escrita nem qualquer
        // outra coisa.
        RegisteredClient simuladorCoreLegado = RegisteredClient.withId("simulador-core-legado-client")
                .clientId(simuladorClientId)
                .clientSecret(passwordEncoder.encode(simuladorClientSecret))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope(SCOPE_CREDITO_CALLBACK)
                .clientSettings(ClientSettings.builder()
                        .setting(CLIENT_CREDENTIALS_AUDIENCE_SETTING, AUD_CREDITO)
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofMinutes(5))
                        .build())
                .build();

        return new InMemoryRegisteredClientRepository(bffGerente, servicoCredito, simuladorCoreLegado);
    }
}
