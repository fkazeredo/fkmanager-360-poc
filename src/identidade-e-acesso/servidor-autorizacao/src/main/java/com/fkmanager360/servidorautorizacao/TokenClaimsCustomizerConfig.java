package com.fkmanager360.servidorautorizacao;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2TokenExchangeAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code papeis} e o papel organizacional grosso do ator (CONTEXT-MAP.md: IdentidadeEAcesso
 * conhece identidade e papeis, nunca politica de negocio) -- carregado no token para que cada
 * Resource Server saiba quem esta operando sem consultar de volta este servidor.
 *
 * <p>Em Token Exchange (ADR-0015), o {@code aud} do token emitido vem do recurso pedido pelo
 * cliente trocador, e {@code sub}/{@code papeis} vem do subject_token original -- decodificado de
 * novo aqui porque a Authentication interna do provider de exchange do Spring Authorization
 * Server nao expoe claims customizadas diretamente.
 */
@Configuration
public class TokenClaimsCustomizerConfig {

    @Bean
    JwtDecoder proprioJwtDecoder(JWKSource<SecurityContext> jwkSource) {
        return NimbusJwtDecoder.withJwkSource(jwkSource).build();
    }

    @Bean
    OAuth2TokenCustomizer<JwtEncodingContext> jwtCustomizer(JwtDecoder proprioJwtDecoder) {
        return context -> {
            if (!OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
                return;
            }

            if (AuthorizationGrantType.TOKEN_EXCHANGE.equals(context.getAuthorizationGrantType())) {
                aplicarClaimsDeTokenExchange(context, proprioJwtDecoder);
            } else {
                aplicarPapeisDoPrincipalAutenticado(context);
            }
        };
    }

    private void aplicarClaimsDeTokenExchange(JwtEncodingContext context, JwtDecoder proprioJwtDecoder) {
        OAuth2TokenExchangeAuthenticationToken grant = context.getAuthorizationGrant();

        Set<String> audiencias = new LinkedHashSet<>();
        audiencias.addAll(grant.getResources());
        audiencias.addAll(grant.getAudiences());
        if (!audiencias.isEmpty()) {
            context.getClaims().audience(new ArrayList<>(audiencias));
        }

        Jwt subjectJwt = proprioJwtDecoder.decode(grant.getSubjectToken());
        context.getClaims().subject(subjectJwt.getSubject());

        List<String> papeis = subjectJwt.getClaimAsStringList("papeis");
        if (papeis != null && !papeis.isEmpty()) {
            context.getClaims().claim("papeis", papeis);
        }
    }

    private void aplicarPapeisDoPrincipalAutenticado(JwtEncodingContext context) {
        Authentication principal = context.getPrincipal();
        if (principal == null || principal.getAuthorities() == null) {
            return;
        }

        List<String> papeis = principal.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(autoridade -> autoridade.startsWith("ROLE_"))
                .map(autoridade -> autoridade.substring("ROLE_".length()))
                .toList();

        if (!papeis.isEmpty()) {
            context.getClaims().claim("papeis", papeis);
        }
    }
}
