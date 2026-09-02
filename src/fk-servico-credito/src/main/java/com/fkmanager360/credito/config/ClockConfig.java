package com.fkmanager360.credito.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * O relogio e injetado, e nao lido de {@code Instant.now()} espalhado pelo codigo, porque
 * "quando isto foi consultado" e um fato de procedencia -- alguem precisa poder fixa-lo num
 * teste e afirmar que ele nao veio de nenhum campo da resposta do host.
 *
 * <p>UTC: instante de captura e ponto na linha do tempo, nao horario local de exibicao.
 */
@Configuration
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
