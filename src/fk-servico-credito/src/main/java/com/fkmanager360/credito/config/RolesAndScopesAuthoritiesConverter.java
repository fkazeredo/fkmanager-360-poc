package com.fkmanager360.credito.config;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Scope vira autoridade grossa {@code SCOPE_x} (conversor padrao do Spring); o claim
 * {@code papeis}, emitido por IdentidadeEAcesso, vira {@code ROLE_x}. As duas perguntas
 * continuam distintas (ADR-0015): scope diz se aquela identidade pode tentar a capacidade, papel
 * e o que o endpoint exige para autorizar o recurso.
 *
 * <p>Copia deliberada da de servico-carteira-clientes, pelo mesmo motivo de
 * {@link AudienceValidator}.
 */
public class RolesAndScopesAuthoritiesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    private final JwtGrantedAuthoritiesConverter scopeConverter = new JwtGrantedAuthoritiesConverter();

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        List<GrantedAuthority> authorities = new ArrayList<>(scopeConverter.convert(jwt));

        List<String> papeis = jwt.getClaimAsStringList("papeis");
        if (papeis != null) {
            papeis.forEach(papel -> authorities.add(new SimpleGrantedAuthority("ROLE_" + papel)));
        }

        return authorities;
    }
}
