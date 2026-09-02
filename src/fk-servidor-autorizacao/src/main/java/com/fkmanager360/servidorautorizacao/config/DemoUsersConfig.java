package com.fkmanager360.servidorautorizacao.config;

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
 * <p>Formato de {@code servidor-autorizacao.demo-users}: {@code login:senha:papel;login:senha:papel}.
 */
@Configuration
public class DemoUsersConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    UserDetailsService userDetailsService(
            @Value("${servidor-autorizacao.demo-users}") String demoUsers,
            PasswordEncoder passwordEncoder) {

        InMemoryUserDetailsManager manager = new InMemoryUserDetailsManager();

        for (String definicao : demoUsers.split(";")) {
            String[] partes = definicao.strip().split(":");
            if (partes.length != 3) {
                throw new IllegalStateException(
                        "servidor-autorizacao.demo-users malformado, esperado login:senha:papel -- " + definicao);
            }
            String login = partes[0];
            String senha = partes[1];
            String papel = partes[2];

            manager.createUser(User.withUsername(login)
                    .password(passwordEncoder.encode(senha))
                    .authorities("ROLE_" + papel)
                    .build());
        }

        return manager;
    }
}
