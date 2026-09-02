package com.fkmanager360.carteiraclientes.adapters.entrada.seguranca;

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
 * {@code papeis}, emitido por IdentidadeEAcesso com os papeis organizacionais grossos do ator,
 * vira {@code ROLE_x}. As duas perguntas continuam distintas (ADR-0015): scope diz se aquela
 * identidade pode tentar a capacidade, papel e o que o endpoint exige para autorizar o recurso.
 */
public class PapeisEEscoposAuthoritiesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    private final JwtGrantedAuthoritiesConverter conversorDeEscopo = new JwtGrantedAuthoritiesConverter();

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        List<GrantedAuthority> autoridades = new ArrayList<>(conversorDeEscopo.convert(jwt));

        List<String> papeis = jwt.getClaimAsStringList("papeis");
        if (papeis != null) {
            papeis.forEach(papel -> autoridades.add(new SimpleGrantedAuthority("ROLE_" + papel)));
        }

        return autoridades;
    }
}
