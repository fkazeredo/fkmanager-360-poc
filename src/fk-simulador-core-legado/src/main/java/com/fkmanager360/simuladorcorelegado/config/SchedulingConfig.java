package com.fkmanager360.simuladorcorelegado.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * {@link TaskScheduler} para o agendamento de processamento com atraso de
 * {@code ProcessadorEfetivacaoLegado} (#0005) -- pool minimo, um unico processamento assincrono
 * por vez e suficiente para esta POC.
 */
@Configuration
public class SchedulingConfig {

    @Bean
    TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("processador-efetivacao-");
        scheduler.initialize();
        return scheduler;
    }
}
