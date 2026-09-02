package com.fkmanager360.carteiraclientes.adapters.entrada.seguranca;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.expression.WebExpressionAuthorizationManager;

/**
 * Este servico e um Resource Server puro: autenticacao por token bearer, sem sessao nem cookie
 * (a sessao pertence ao bff-gerente, ADR-0015). CSRF fica desligado deliberadamente -- protege
 * contra forjar requisicao autenticada por cookie, e este servico nao autentica por cookie.
 */
@Configuration
@EnableWebSecurity
public class SegurancaConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {
        http
                .sessionManagement(sessao -> sessao.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(autorizacao -> autorizacao
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        // Scope grosso e papel organizacional sao perguntas distintas (ADR-0015):
                        // scope diz se a identidade pode tentar a capacidade, papel e o que este
                        // endpoint exige. Ambos precisam valer.
                        .requestMatchers("/carteira/**").access(new WebExpressionAuthorizationManager(
                                "hasAuthority('SCOPE_carteira.leitura') and hasRole('GERENTE_RELACIONAMENTO')"))
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)));

        return http.build();
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new PapeisEEscoposAuthoritiesConverter());
        return converter;
    }

    /**
     * Backs off em teste: {@code @TestConfiguration} de S6 declara seu proprio {@link JwtDecoder}
     * com chave publica fixa, sem depender de servidor-autorizacao estar de pe. Sem o
     * {@code @ConditionalOnMissingBean}, este metodo tentaria descoberta OIDC contra uma
     * issuer-uri falsa no boot do contexto de teste, antes mesmo de o teste rodar.
     */
    @Bean
    @ConditionalOnMissingBean(JwtDecoder.class)
    JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri,
            @Value("${carteira-clientes.seguranca.audience-esperada}") String audienceEsperada) {

        NimbusJwtDecoder decoder = (NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(issuerUri);

        OAuth2TokenValidator<Jwt> validadorPadrao = JwtValidators.createDefaultWithIssuer(issuerUri);
        OAuth2TokenValidator<Jwt> validadorAudience = new AudienceValidator(audienceEsperada);

        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validadorPadrao, validadorAudience));

        return decoder;
    }
}
