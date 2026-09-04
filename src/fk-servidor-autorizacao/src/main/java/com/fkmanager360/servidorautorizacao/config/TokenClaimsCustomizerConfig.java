package com.fkmanager360.servidorautorizacao.config;

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
    JwtDecoder ownJwtDecoder(JWKSource<SecurityContext> jwkSource) {
        return NimbusJwtDecoder.withJwkSource(jwkSource).build();
    }

    @Bean
    OAuth2TokenCustomizer<JwtEncodingContext> jwtCustomizer(JwtDecoder ownJwtDecoder) {
        return context -> {
            if (!OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
                return;
            }

            if (AuthorizationGrantType.TOKEN_EXCHANGE.equals(context.getAuthorizationGrantType())) {
                applyTokenExchangeClaims(context, ownJwtDecoder);
            } else if (AuthorizationGrantType.CLIENT_CREDENTIALS.equals(context.getAuthorizationGrantType())) {
                applyClientCredentialsAudience(context);
            } else {
                applyAuthenticatedPrincipalRoles(context);
            }
        };
    }

    /**
     * {@code client_credentials} (#0005): o unico destino possivel deste client e fixo, registrado
     * como {@link RegisteredClientsConfig#CLIENT_CREDENTIALS_AUDIENCE_SETTING} na propria
     * {@code RegisteredClient} -- sem isso o token sairia sem {@code aud} e seria recusado pelo
     * {@code AudienceValidator} do Resource Server (so o ramo de Token Exchange preenchia
     * audience ate #0005). Nunca carrega {@code papeis}: maquina-a-maquina nao tem sujeito humano,
     * e so IdentidadeEAcesso emite papel para quem tem (CONTEXT-MAP.md).
     */
    private void applyClientCredentialsAudience(JwtEncodingContext context) {
        String audience = context.getRegisteredClient().getClientSettings()
                .getSetting(RegisteredClientsConfig.CLIENT_CREDENTIALS_AUDIENCE_SETTING);
        if (audience != null && !audience.isBlank()) {
            context.getClaims().audience(List.of(audience));
        }
    }

    private void applyTokenExchangeClaims(JwtEncodingContext context, JwtDecoder ownJwtDecoder) {
        OAuth2TokenExchangeAuthenticationToken grant = context.getAuthorizationGrant();

        Set<String> audiences = new LinkedHashSet<>();
        audiences.addAll(grant.getResources());
        audiences.addAll(grant.getAudiences());
        if (!audiences.isEmpty()) {
            context.getClaims().audience(new ArrayList<>(audiences));
        }

        Jwt subjectJwt = ownJwtDecoder.decode(grant.getSubjectToken());
        context.getClaims().subject(subjectJwt.getSubject());

        List<String> papeis = subjectJwt.getClaimAsStringList("papeis");
        if (papeis != null && !papeis.isEmpty()) {
            context.getClaims().claim("papeis", papeis);
        }
    }

    private void applyAuthenticatedPrincipalRoles(JwtEncodingContext context) {
        Authentication principal = context.getPrincipal();
        if (principal == null || principal.getAuthorities() == null) {
            return;
        }

        List<String> papeis = principal.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith("ROLE_"))
                .map(authority -> authority.substring("ROLE_".length()))
                .toList();

        if (!papeis.isEmpty()) {
            context.getClaims().claim("papeis", papeis);
        }
    }
}
