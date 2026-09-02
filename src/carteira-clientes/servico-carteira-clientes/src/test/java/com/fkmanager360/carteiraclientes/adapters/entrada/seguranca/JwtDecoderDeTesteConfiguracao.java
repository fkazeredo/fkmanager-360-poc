package com.fkmanager360.carteiraclientes.adapters.entrada.seguranca;

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
 * servidor-autorizacao. Usa a chave publica do par gerado em {@link JwtDeTesteSuporte}, com o
 * mesmo {@link AudienceValidator} de producao -- e assim que o teste prova a validacao de
 * audience de verdade, e nao apenas confia em claims injetadas.
 */
@TestConfiguration
class JwtDecoderDeTesteConfiguracao {

    static final String AUDIENCE_ESPERADA = "servico-carteira-clientes";

    @Bean
    JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(JwtDeTesteSuporte.chavePublica()).build();

        OAuth2TokenValidator<Jwt> validador = new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(), new AudienceValidator(AUDIENCE_ESPERADA));
        decoder.setJwtValidator(validador);

        return decoder;
    }
}
