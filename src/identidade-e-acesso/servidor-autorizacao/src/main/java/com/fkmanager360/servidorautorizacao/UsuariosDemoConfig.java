package com.fkmanager360.servidorautorizacao;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

/**
 * Usuarios de demonstracao, configurados deterministicamente -- sem identidade_db, porque nenhum
 * comportamento de #0001 exige identidade duravel (ADR-0014). {@code papeis} e um papel
 * organizacional grosso (CONTEXT-MAP.md): autoridade financeira nao mora aqui (ADR-0015).
 *
 * <p>Formato de {@code servidor-autorizacao.usuarios-demo}: {@code login:senha:papel;login:senha:papel}.
 */
@Configuration
public class UsuariosDemoConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    UserDetailsService userDetailsService(
            @Value("${servidor-autorizacao.usuarios-demo}") String usuariosDemo,
            PasswordEncoder passwordEncoder) {

        InMemoryUserDetailsManager gerenciador = new InMemoryUserDetailsManager();

        for (String definicao : usuariosDemo.split(";")) {
            String[] partes = definicao.strip().split(":");
            if (partes.length != 3) {
                throw new IllegalStateException(
                        "servidor-autorizacao.usuarios-demo malformado, esperado login:senha:papel -- " + definicao);
            }
            String login = partes[0];
            String senha = partes[1];
            String papel = partes[2];

            gerenciador.createUser(User.withUsername(login)
                    .password(passwordEncoder.encode(senha))
                    .authorities("ROLE_" + papel)
                    .build());
        }

        return gerenciador;
    }
}
