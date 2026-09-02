package com.fkmanager360.credito.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Substitui, so em teste, o {@link JwtDecoder} que faria descoberta OIDC contra
 * servidor-autorizacao. Usa a chave publica do par gerado em {@link JwtTestSupport}, com o mesmo
 * {@link AudienceValidator} de producao -- e assim que o teste prova a validacao de audience de
 * verdade, e nao apenas confia em claims injetadas.
 */
@TestConfiguration
class JwtDecoderTestConfig {

    static final String EXPECTED_AUDIENCE = "servico-credito";

    @Bean
    JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(JwtTestSupport.publicKey()).build();

        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(), new AudienceValidator(EXPECTED_AUDIENCE));
        decoder.setJwtValidator(validator);

        return decoder;
    }
}
