package com.fkmanager360.servidorautorizacao.config;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2TokenExchangeAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A politica do <b>emissor</b> sobre Token Exchange (RFC 8693), que nao e a mesma coisa que a
 * validacao feita pelo destino (ADR-0015). Sem ela, o servidor-autorizacao copiaria para o token
 * emitido qualquer {@code resource}/{@code audience} e qualquer {@code scope} que o client
 * pedisse -- ver {@link TokenClaimsCustomizerConfig}. Duas regras, ambas verificadas <b>antes</b>
 * de qualquer token ser gerado:
 *
 * <ol>
 *   <li><b>Target na allow-list.</b> Cada client declara para quem pode trocar
 *       ({@value #ALLOWED_TARGETS_SETTING} na propria {@link RegisteredClient}, que e o lugar
 *       nativo do Spring Authorization Server para configuracao especifica de um client).</li>
 *   <li><b>Sem amplificacao de privilegio.</b> O scope pedido precisa estar contido no scope do
 *       <b>subject token</b>. Uma cadeia de delegacao pode estreitar capability -- e o desenho
 *       correto, e o que servico-credito faz ao pedir so {@code carteira.leitura} --, mas nunca
 *       ganhar capability que o token original nao tinha. Sem esta regra, um servico intermediario
 *       comprometido trocaria um token de leitura por um de escrita, e a cadeia de delegacao
 *       viraria escada de privilegio.</li>
 * </ol>
 *
 * <p>A validacao de que o scope pedido cabe nos scopes <b>registrados do client</b> continua
 * sendo do provider padrao, e as duas se somam: o client so pode pedir o que foi registrado
 * <i>e</i> o que o usuario ja tinha.
 *
 * <p>Decora (nao substitui) o {@code OAuth2TokenExchangeAuthenticationProvider} padrao no lugar
 * dele, na lista de providers do endpoint de token ({@link AuthorizationServerSecurityConfig}).
 * Decorator, nao um provider adicional na cadeia: o {@code ProviderManager} trata uma
 * {@link AuthenticationException} lancada por um provider como "tente o proximo da lista", nunca
 * como rejeicao definitiva -- prepend-and-throw deixaria o provider padrao, mais adiante na
 * mesma lista, autenticar a mesma requisicao e emitir o token mesmo assim. Delegar por chamada de
 * metodo direta e o unico jeito de garantir que nenhum token seja gerado quando a politica recusa.
 */
class TokenExchangePolicyAuthenticationProvider implements AuthenticationProvider {

    static final String ALLOWED_TARGETS_SETTING = "fk.token-exchange.allowed-targets";
    private static final String INVALID_TARGET = "invalid_target";
    private static final String INVALID_SCOPE = "invalid_scope";

    private final AuthenticationProvider delegate;
    private final JwtDecoder subjectTokenDecoder;

    TokenExchangePolicyAuthenticationProvider(AuthenticationProvider delegate, JwtDecoder subjectTokenDecoder) {
        this.delegate = delegate;
        this.subjectTokenDecoder = subjectTokenDecoder;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        OAuth2TokenExchangeAuthenticationToken grant = (OAuth2TokenExchangeAuthenticationToken) authentication;
        RegisteredClient registeredClient = registeredClientDoGrant(grant);

        exigirTargetNaAllowList(grant, registeredClient);
        exigirScopeContidoNoSubjectToken(grant, registeredClient);

        return delegate.authenticate(authentication);
    }

    private void exigirTargetNaAllowList(
            OAuth2TokenExchangeAuthenticationToken grant, RegisteredClient registeredClient) {

        Set<String> targetsSolicitados = new LinkedHashSet<>();
        targetsSolicitados.addAll(grant.getResources());
        targetsSolicitados.addAll(grant.getAudiences());

        if (targetsSolicitados.isEmpty()) {
            return;
        }

        Set<String> targetsPermitidos = registeredClient.getClientSettings().getSetting(ALLOWED_TARGETS_SETTING);
        if (targetsPermitidos == null || !targetsPermitidos.containsAll(targetsSolicitados)) {
            throw new OAuth2AuthenticationException(new OAuth2Error(
                    INVALID_TARGET,
                    "O client %s nao esta autorizado a solicitar token para %s"
                            .formatted(registeredClient.getClientId(), targetsSolicitados),
                    null));
        }
    }

    private void exigirScopeContidoNoSubjectToken(
            OAuth2TokenExchangeAuthenticationToken grant, RegisteredClient registeredClient) {

        Set<String> scopesSolicitados = grant.getScopes();
        if (scopesSolicitados == null || scopesSolicitados.isEmpty()) {
            // Sem scope pedido, o provider padrao emite os scopes do proprio subject token -- que
            // por definicao nao amplia nada.
            return;
        }

        Set<String> scopesDoSubjectToken = scopesDoSubjectToken(grant);
        if (!scopesDoSubjectToken.containsAll(scopesSolicitados)) {
            throw new OAuth2AuthenticationException(new OAuth2Error(
                    INVALID_SCOPE,
                    "O client %s pediu scope que o subject token nao possui: %s"
                            .formatted(registeredClient.getClientId(), scopesSolicitados),
                    null));
        }
    }

    private Set<String> scopesDoSubjectToken(OAuth2TokenExchangeAuthenticationToken grant) {
        Jwt subjectToken;
        try {
            subjectToken = subjectTokenDecoder.decode(grant.getSubjectToken());
        } catch (JwtException e) {
            // Um subject token ilegivel nao concede scope nenhum. Quem produz o erro definitivo
            // sobre o token em si e o provider padrao, adiante; aqui basta nao autorizar nada.
            return Set.of();
        }

        // O claim "scope" aparece nas duas formas conforme quem emitiu o token: lista JSON (e o
        // que este servidor emite) ou string delimitada por espaco (RFC 6749). Ler so uma delas
        // faria a politica enxergar zero scopes e recusar tudo -- falha fechada, mas pela razao
        // errada, e indistinguivel de uma amplificacao real.
        Object scope = subjectToken.getClaim("scope");
        return switch (scope) {
            case null -> Set.of();
            case Collection<?> lista -> lista.stream()
                    .map(String::valueOf)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            case String texto when !texto.isBlank() ->
                    new LinkedHashSet<>(Arrays.asList(texto.trim().split("\\s+")));
            default -> Set.of();
        };
    }

    private static RegisteredClient registeredClientDoGrant(OAuth2TokenExchangeAuthenticationToken grant) {
        if (grant.getPrincipal() instanceof OAuth2ClientAuthenticationToken clientPrincipal
                && clientPrincipal.getRegisteredClient() != null) {
            return clientPrincipal.getRegisteredClient();
        }
        throw new OAuth2AuthenticationException(new OAuth2Error(INVALID_TARGET, "Client nao identificado", null));
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return delegate.supports(authentication);
    }
}
