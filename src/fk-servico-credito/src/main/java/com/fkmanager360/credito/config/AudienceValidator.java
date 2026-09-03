package com.fkmanager360.credito.config;

import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Cada Resource Server valida localmente que o token foi emitido para ele (ADR-0015): tokens sao
 * audience-restricted, e um token valido emitido para outro destino e recusado aqui, nao apenas
 * confiado por vir assinado corretamente.
 *
 * <p>Copia deliberada da classe equivalente de servico-carteira-clientes: entidades e utilitarios
 * nao atravessam bounded contexts (ADR-0011), e um modulo comum acoplaria os dois servicos so
 * para poupar trinta linhas -- exatamente o que "monorepo preparado para polyrepo" recusa.
 */
@RequiredArgsConstructor
public class AudienceValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error INVALID_AUDIENCE_ERROR =
            new OAuth2Error("invalid_token", "O token nao foi emitido para este Resource Server", null);

    private final String expectedAudience;

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        if (jwt.getAudience() != null && jwt.getAudience().contains(expectedAudience)) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(INVALID_AUDIENCE_ERROR);
    }
}
