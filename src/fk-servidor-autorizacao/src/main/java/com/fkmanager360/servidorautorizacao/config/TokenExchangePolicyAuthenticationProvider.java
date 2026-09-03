package com.fkmanager360.servidorautorizacao.config;

import lombok.RequiredArgsConstructor;
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
 * pedisse -- ver {@link TokenClaimsCustomizerConfig}. Tres regras, todas verificadas <b>antes</b>
 * de qualquer token ser gerado (achado I8 do review de #0002 endureceu as duas primeiras):
 *
 * <ol>
 *   <li><b>Exatamente um target por troca.</b> Nenhum target, ou mais de um simultaneamente, e
 *       recusado -- nao so target fora da allow-list. Sem este limite, um client com dois
 *       destinos permitidos individualmente (como {@code bff-gerente}, autorizado tanto a
 *       {@code servico-carteira-clientes} quanto a {@code servico-credito}) poderia pedir os dois
 *       na mesma troca e receber um unico token com {@code aud} multipla -- exatamente o "token
 *       de usuario multi-audience" que a plataforma declara nao existir (ADR-0015). Cada
 *       Resource Server so aceita token emitido para ele; um token multi-audience alcancaria mais
 *       de um deles com a mesma credencial.</li>
 *   <li><b>Target na allow-list.</b> O unico target da troca precisa estar entre os que o client
 *       pode alcancar ({@value #ALLOWED_TARGETS_SETTING} na propria {@link RegisteredClient}, o
 *       lugar nativo do Spring Authorization Server para configuracao especifica de um client).</li>
 *   <li><b>Scope explicito, e sem amplificacao de privilegio.</b> Toda troca nesta plataforma
 *       precisa declarar {@code scope} -- omitir o parametro nao e um atalho para "os scopes do
 *       subject token", e sim uma requisicao recusada. Isso fecha um vetor que dependia do
 *       comportamento do provider padrao continuar sendo o que e hoje (autorizar um conjunto
 *       vazio, e nao herdar scope algum): exigir explicitacao remove essa dependencia. Com scope
 *       presente, ele precisa estar contido no scope do <b>subject token</b> -- uma cadeia de
 *       delegacao pode estreitar capability (o que servico-credito faz ao pedir so
 *       {@code carteira.leitura}), mas nunca ganhar capability que o token original nao tinha.</li>
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
@RequiredArgsConstructor
class TokenExchangePolicyAuthenticationProvider implements AuthenticationProvider {

    static final String ALLOWED_TARGETS_SETTING = "fk.token-exchange.allowed-targets";
    private static final String INVALID_TARGET = "invalid_target";
    private static final String INVALID_SCOPE = "invalid_scope";

    private final AuthenticationProvider delegate;
    private final JwtDecoder subjectTokenDecoder;

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        OAuth2TokenExchangeAuthenticationToken grant = (OAuth2TokenExchangeAuthenticationToken) authentication;
        RegisteredClient registeredClient = registeredClientDoGrant(grant);

        String target = exigirExatamenteUmTarget(grant, registeredClient);
        exigirTargetNaAllowList(target, registeredClient);
        exigirScopeExplicitoEContidoNoSubjectToken(grant, registeredClient);

        return delegate.authenticate(authentication);
    }

    private String exigirExatamenteUmTarget(OAuth2TokenExchangeAuthenticationToken grant, RegisteredClient registeredClient) {
        Set<String> targets = new LinkedHashSet<>();
        targets.addAll(grant.getResources());
        targets.addAll(grant.getAudiences());

        if (targets.size() != 1) {
            throw new OAuth2AuthenticationException(new OAuth2Error(
                    INVALID_TARGET,
                    "Cada troca nesta plataforma precisa ter exatamente um destino (resource ou "
                            + "audience); o client %s pediu %d"
                            .formatted(registeredClient.getClientId(), targets.size()),
                    null));
        }
        return targets.iterator().next();
    }

    private void exigirTargetNaAllowList(String target, RegisteredClient registeredClient) {
        Set<String> targetsPermitidos = registeredClient.getClientSettings().getSetting(ALLOWED_TARGETS_SETTING);
        if (targetsPermitidos == null || !targetsPermitidos.contains(target)) {
            throw new OAuth2AuthenticationException(new OAuth2Error(
                    INVALID_TARGET,
                    "O client %s nao esta autorizado a solicitar token para %s"
                            .formatted(registeredClient.getClientId(), target),
                    null));
        }
    }

    private void exigirScopeExplicitoEContidoNoSubjectToken(
            OAuth2TokenExchangeAuthenticationToken grant, RegisteredClient registeredClient) {

        Set<String> scopesSolicitados = grant.getScopes();
        if (scopesSolicitados == null || scopesSolicitados.isEmpty()) {
            throw new OAuth2AuthenticationException(new OAuth2Error(
                    INVALID_SCOPE,
                    "Toda troca nesta plataforma precisa declarar scope explicitamente; o client "
                            + "%s nao pediu nenhum".formatted(registeredClient.getClientId()),
                    null));
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
