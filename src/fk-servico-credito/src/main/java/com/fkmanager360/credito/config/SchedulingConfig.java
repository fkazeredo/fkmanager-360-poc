package com.fkmanager360.credito.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Habilita {@code @Scheduled} para o dispatcher de efetivacao (plano #0004, secao 1). Sem
 * assumir instancia unica -- o mesmo {@code @Scheduled} do Spring roda em toda instancia, e a
 * concorrencia e resolvida por {@code FOR UPDATE SKIP LOCKED} + fencing em
 * {@code JdbcEntregasEfetivacaoAdapter}, nunca por eleicao de lider (OD-1).
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
