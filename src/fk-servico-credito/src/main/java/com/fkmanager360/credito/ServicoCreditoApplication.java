package com.fkmanager360.credito;

import com.fkmanager360.credito.application.PoliticaRetryEntrega;
import com.fkmanager360.credito.application.port.out.DadosCreditoCorePort;
import com.fkmanager360.credito.application.port.out.DireitoDeAtendimentoPort;
import com.fkmanager360.credito.application.port.out.EntregasEfetivacaoPort;
import com.fkmanager360.credito.application.port.out.InstrucaoEfetivacaoCorePort;
import com.fkmanager360.credito.application.port.out.RegistroIdempotenciaPort;
import com.fkmanager360.credito.application.port.out.SolicitacoesAumentoLimitePort;
import com.fkmanager360.credito.application.port.out.ResultadoEfetivacaoPort;
import com.fkmanager360.credito.application.usecase.ConsultarLimiteChequeEspecialVigente;
import com.fkmanager360.credito.application.usecase.DecidirSolicitacaoAumentoLimite;
import com.fkmanager360.credito.application.usecase.EntregarInstrucoesEfetivacao;
import com.fkmanager360.credito.application.usecase.RegistrarResultadoEfetivacao;
import com.fkmanager360.credito.application.usecase.RegistrarSolicitacaoAumentoLimite;
import com.fkmanager360.credito.domain.MotorDecisaoCredito;
import com.fkmanager360.credito.domain.PoliticaCreditoV1;
import com.fkmanager360.credito.domain.VersaoPoliticaCredito;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Random;

/**
 * Contexto Credito, materializado em codigo por #0002 (leitura, sem persistencia) e estendido por
 * #0003 com o primeiro comportamento de estado duravel: a submissao com decisao automatica. A
 * partir daqui {@code credito_db}, Flyway e a persistencia existem deliberadamente (ADR-0010,
 * ADR-0014) -- o comentario anterior, que afirmava a ausencia de persistencia, ficou obsoleto com
 * este ticket.
 */
@SpringBootApplication
public class ServicoCreditoApplication {

    public static void main(String[] args) {
        SpringApplication.run(ServicoCreditoApplication.class, args);
    }

    // O caso de uso e um POJO: quem o monta e a borda, nao uma anotacao dentro da aplicacao.
    @Bean
    ConsultarLimiteChequeEspecialVigente consultarLimiteChequeEspecialVigente(
            DireitoDeAtendimentoPort direitoDeAtendimento, DadosCreditoCorePort dadosCreditoCore) {
        return new ConsultarLimiteChequeEspecialVigente(direitoDeAtendimento, dadosCreditoCore);
    }

    /**
     * {@code MotorDecisaoCredito} valida no PROPRIO CONSTRUTOR que {@code versaoVigente} tem uma
     * {@code PoliticaCredito} correspondente entre as conhecidas (guardrail do dominio, Stage 1) --
     * e essa validacao acontece na CRIACAO DESTE BEAN, ou seja, no boot do Spring. Um valor de
     * {@code credito.politica.versao-vigente} sem implementacao registrada ja faz a aplicacao
     * FALHAR AO SUBIR, sem necessidade de {@code @PostConstruct} adicional.
     */
    @Bean
    MotorDecisaoCredito motorDecisaoCredito(
            @Value("${credito.politica.versao-vigente:v1}") String versaoVigente) {
        return new MotorDecisaoCredito(List.of(new PoliticaCreditoV1()), new VersaoPoliticaCredito(versaoVigente));
    }

    /**
     * {@code DecidirSolicitacaoAumentoLimite} (Fase 2/3, TX2) e injetado em
     * {@code RegistrarSolicitacaoAumentoLimite} (Fase 0/1, TX1) -- e nao o contrario -- porque a
     * submissao e quem decide, na MESMA requisicao, disparar a decisao logo apos criar a
     * solicitacao (User Story 33). Confirmado lendo o construtor de
     * {@code RegistrarSolicitacaoAumentoLimite}.
     */
    @Bean
    DecidirSolicitacaoAumentoLimite decidirSolicitacaoAumentoLimite(
            SolicitacoesAumentoLimitePort solicitacoes, MotorDecisaoCredito motorDecisaoCredito) {
        return new DecidirSolicitacaoAumentoLimite(solicitacoes, motorDecisaoCredito);
    }

    @Bean
    RegistrarSolicitacaoAumentoLimite registrarSolicitacaoAumentoLimite(
            DireitoDeAtendimentoPort direitoDeAtendimento,
            DadosCreditoCorePort dadosCreditoCore,
            RegistroIdempotenciaPort registroIdempotencia,
            SolicitacoesAumentoLimitePort solicitacoes,
            MotorDecisaoCredito motorDecisaoCredito,
            DecidirSolicitacaoAumentoLimite decidirSolicitacaoAumentoLimite) {
        return new RegistrarSolicitacaoAumentoLimite(
                direitoDeAtendimento, dadosCreditoCore, registroIdempotencia, solicitacoes,
                motorDecisaoCredito, decidirSolicitacaoAumentoLimite);
    }

    /**
     * Backoff exponencial com jitter do dispatcher de efetivacao (plano #0004, secao 5 -- OD-2).
     * {@link Random} sem seed: o jitter e so para espalhar retries concorrentes no tempo, nunca
     * para reproduzir um teste -- S1/S2 injetam seeds proprias.
     */
    @Bean
    PoliticaRetryEntrega politicaRetryEntrega(
            @Value("${credito.efetivacao.entrega.backoff-base:PT1S}") Duration backoffBase,
            @Value("${credito.efetivacao.entrega.backoff-teto:PT4S}") Duration backoffTeto,
            @Value("${credito.efetivacao.entrega.jitter-fator:0.2}") double jitterFator) {
        return new PoliticaRetryEntrega(backoffBase, backoffTeto, jitterFator, new Random());
    }

    /**
     * O dispatcher de efetivacao (spec, secao "Dispatcher"; plano #0004, secoes 1 e 5): tentativa
     * inicial mais ate 3 retries ({@code max-tentativas=4}), lease de claim de 30s por default.
     */
    @Bean
    EntregarInstrucoesEfetivacao entregarInstrucoesEfetivacao(
            EntregasEfetivacaoPort entregasEfetivacaoPort,
            InstrucaoEfetivacaoCorePort instrucaoEfetivacaoCorePort,
            PoliticaRetryEntrega politicaRetryEntrega,
            Clock clock,
            @Value("${credito.efetivacao.entrega.max-tentativas:4}") int maxTentativas,
            @Value("${credito.efetivacao.entrega.lease:PT30S}") Duration lease) {
        return new EntregarInstrucoesEfetivacao(
                entregasEfetivacaoPort, instrucaoEfetivacaoCorePort, politicaRetryEntrega, clock, maxTentativas, lease);
    }

    /**
     * Caso de uso UNICO de conclusao da efetivacao (ADR-0009; #0004, Objetivo) -- ver Javadoc de
     * {@link RegistrarResultadoEfetivacao}. Hoje o unico chamador e
     * {@code JpaEntregasEfetivacaoAdapter#concluirComFalhaDefinitiva}; #0005/#0006 acrescentam
     * outras entradas para o MESMO bean, nunca uma segunda implementacao da regra.
     */
    @Bean
    RegistrarResultadoEfetivacao registrarResultadoEfetivacao(ResultadoEfetivacaoPort resultadoEfetivacaoPort) {
        return new RegistrarResultadoEfetivacao(resultadoEfetivacaoPort);
    }
}
