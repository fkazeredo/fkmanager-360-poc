package com.fkmanager360.carteiraclientes.adapters.entrada.seguranca;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Cada Resource Server valida localmente que o token foi emitido para ele (ADR-0015): tokens sao
 * audience-restricted, e um token valido emitido para outro destino e recusado aqui, nao apenas
 * confiado por vir assinado corretamente.
 */
public class AudienceValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error ERRO_AUDIENCE_INVALIDA =
            new OAuth2Error("invalid_token", "O token nao foi emitido para este Resource Server", null);

    private final String audienceEsperada;

    public AudienceValidator(String audienceEsperada) {
        this.audienceEsperada = audienceEsperada;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        if (jwt.getAudience() != null && jwt.getAudience().contains(audienceEsperada)) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(ERRO_AUDIENCE_INVALIDA);
    }
}
