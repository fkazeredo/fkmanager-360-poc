package com.fkmanager360.credito.application.usecase;

import com.fkmanager360.credito.application.PoliticaRetryEntrega;
import com.fkmanager360.credito.application.ResultadoCicloReconciliacao;
import com.fkmanager360.credito.application.port.out.AlertaOperacionalPort;
import com.fkmanager360.credito.application.port.out.ConsultaStatusEfetivacaoCorePort;
import com.fkmanager360.credito.application.port.out.EfetivacaoReconciliacaoReclamada;
import com.fkmanager360.credito.application.port.out.ReclamacaoReconciliacao;
import com.fkmanager360.credito.application.port.out.ReconciliacaoEfetivacaoPort;
import com.fkmanager360.credito.application.port.out.ResultadoConsultaStatusCore;
import com.fkmanager360.credito.application.port.out.ResultadoEfetivacaoRecebido;
import com.fkmanager360.credito.application.port.out.ResultadoIndeterminacao;
import com.fkmanager360.credito.application.port.out.ResultadoRegistroEfetivacao;
import com.fkmanager360.credito.application.port.out.TransacaoPort;
import com.fkmanager360.credito.domain.AtorSistema;
import com.fkmanager360.credito.domain.ProtocoloCore;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * O reconciliador de efetivacao (spec, secao "Reconciliacao"; ADR-0009, emenda): fronteira estrita
 * com o dispatcher de #0004 -- <b>o dispatcher entrega, o reconciliador pergunta</b>. Nunca reenvia
 * a instrucao; nunca duplica a logica de transicao, que continua exclusivamente em
 * {@link RegistrarResultadoEfetivacao} (ADR-0009: caso de uso unico de conclusao). Entrega
 * exatamente UM ciclo por chamada -- o loop de "ate {@code lote} ciclos por tick" pertence ao
 * adapter de agendamento (mesma decisao do Owner de #0004, OD-1), nunca a este caso de uso, que
 * permanece Java puro e testavel em S2 com fakes (ADR-0018).
 *
 * <p><b>TX-A -&gt; HTTP fora de TX -&gt; TX-B unica</b> (guardrail normativo do Owner, #0006): o
 * claim (TX-A) e a consulta ao Core acontecem fora de qualquer transacao PostgreSQL; a aplicacao do
 * resultado -- fencing, conclusao/indeterminacao e bookkeeping da propria agenda -- e UMA unica
 * unidade transacional via {@link TransacaoPort}, nunca duas transacoes sucessivas. Ordem global de
 * locks preservada: {@code reconciliacao_efetivacao} sempre antes de
 * {@code solicitacao_aumento_limite} -- o reconciliador nunca toca {@code outbox_entrega}, entao as
 * tres vias (callback, dispatcher, reconciliacao) nunca se dao-lock mutuamente.
 *
 * <p><b>O resultado de {@code RegistrarResultadoEfetivacao} governa se a reconciliacao termina</b>
 * (guardrail normativo do Owner): {@code reconciliacao_efetivacao} so vira {@code CONCLUIDA} quando
 * a solicitacao esta efetivamente terminal -- nunca quando o Core respondeu {@code Efetivada} mas o
 * caso de uso recusou aplicar (protocolo ou limite incoerente). Ver {@link #aplicarResultadoAutoritativo}.
 *
 * <p><b>Pos-indeterminacao e polling de recuperacao de baixa frequencia ate conclusao autoritativa,
 * deliberado</b> (spec, secao "Reconciliacao"): uma vez {@code indeterminada_em} preenchido, todo
 * ciclo subsequente usa o backoff LONGO, nunca volta ao curto -- ver {@link #reagendarOuIndeterminar}.
 */
public class ReconciliarEfetivacoes {

    private final ReconciliacaoEfetivacaoPort reconciliacao;
    private final ConsultaStatusEfetivacaoCorePort core;
    private final RegistrarResultadoEfetivacao registrarResultadoEfetivacao;
    private final AlertaOperacionalPort alertaOperacional;
    private final TransacaoPort transacao;
    private final PoliticaRetryEntrega politicaRetryConsulta;
    private final Clock clock;
    private final Duration lease;
    private final Duration backoffLongo;

    public ReconciliarEfetivacoes(
            ReconciliacaoEfetivacaoPort reconciliacao,
            ConsultaStatusEfetivacaoCorePort core,
            RegistrarResultadoEfetivacao registrarResultadoEfetivacao,
            AlertaOperacionalPort alertaOperacional,
            TransacaoPort transacao,
            PoliticaRetryEntrega politicaRetryConsulta,
            Clock clock,
            Duration lease,
            Duration backoffLongo) {
        this.reconciliacao = Objects.requireNonNull(reconciliacao, "reconciliacao e obrigatoria");
        this.core = Objects.requireNonNull(core, "core e obrigatorio");
        this.registrarResultadoEfetivacao =
                Objects.requireNonNull(registrarResultadoEfetivacao, "registrarResultadoEfetivacao e obrigatorio");
        this.alertaOperacional = Objects.requireNonNull(alertaOperacional, "alertaOperacional e obrigatorio");
        this.transacao = Objects.requireNonNull(transacao, "transacao e obrigatoria");
        this.politicaRetryConsulta = Objects.requireNonNull(politicaRetryConsulta, "politicaRetryConsulta e obrigatoria");
        this.clock = Objects.requireNonNull(clock, "clock e obrigatorio");
        this.lease = Objects.requireNonNull(lease, "lease e obrigatoria");
        this.backoffLongo = Objects.requireNonNull(backoffLongo, "backoffLongo e obrigatoria");
    }

    public ResultadoCicloReconciliacao executarUmCiclo() {
        ReclamacaoReconciliacao reclamacao = reconciliacao.reclamarProxima(clock.instant(), lease);

        if (reclamacao instanceof ReclamacaoReconciliacao.NenhumaPendente) {
            return new ResultadoCicloReconciliacao.SemPendente();
        }
        if (reclamacao instanceof ReclamacaoReconciliacao.JaTerminalDescartada jaTerminal) {
            return new ResultadoCicloReconciliacao.JaTerminalAoReclamar(jaTerminal.statusPersistido());
        }

        EfetivacaoReconciliacaoReclamada claim = ((ReclamacaoReconciliacao.Reclamada) reclamacao).claim();

        // HTTP FORA de qualquer transacao PostgreSQL (guardrail normativo do Owner): recuperavel
        // por ProtocoloCore quando conhecido, por EfetivacaoId quando o aceite se perdeu.
        ResultadoConsultaStatusCore resposta = claim.protocoloConhecido()
                .map(core::consultarPorProtocolo)
                .orElseGet(() -> core.consultarPorEfetivacaoId(claim.efetivacaoId()));

        Instant agora = clock.instant();

        ResultadoCicloReconciliacao resultado = transacao.executar(() -> aplicarSobClaim(claim, resposta, agora));

        // Observabilidade DEPOIS do commit, dirigida exclusivamente pelo sinal persistido -- nunca
        // por uma tentativa (decisao do Owner: estado funcional primeiro, sem exactly-once entre
        // PostgreSQL, log e Micrometer).
        if (resultado instanceof ResultadoCicloReconciliacao.IndeterminadaAgora) {
            alertaOperacional.efetivacaoIndeterminada(claim.efetivacaoId(), claim.solicitacaoId(), agora);
        }

        return resultado;
    }

    private ResultadoCicloReconciliacao aplicarSobClaim(
            EfetivacaoReconciliacaoReclamada claim, ResultadoConsultaStatusCore resposta, Instant agora) {

        if (!reconciliacao.claimAindaValido(claim)) {
            return new ResultadoCicloReconciliacao.DescartadoPorFencing();
        }

        return switch (resposta) {
            case ResultadoConsultaStatusCore.Efetivada efetivada -> aplicarResultadoAutoritativo(
                    claim, new ResultadoEfetivacaoRecebido.Sucesso(efetivada.limiteEfetivadoCentavos()),
                    Optional.of(efetivada.protocolo()), agora);
            case ResultadoConsultaStatusCore.FalhaDefinitiva falha -> aplicarResultadoAutoritativo(
                    claim, new ResultadoEfetivacaoRecebido.FalhaDefinitiva(falha.motivo()), Optional.empty(), agora);
            case ResultadoConsultaStatusCore.EmProcessamento ignored -> reagendarOuIndeterminar(claim, agora, false);
            case ResultadoConsultaStatusCore.Desconhecida ignored -> reagendarOuIndeterminar(claim, agora, false);
            case ResultadoConsultaStatusCore.Indeterminada ignored -> reagendarOuIndeterminar(claim, agora, false);
        };
    }

    /**
     * O resultado de {@link RegistrarResultadoEfetivacao#executar} governa a terminalizacao da
     * reconciliacao -- mapeamento exaustivo e normativo (guardrail do Owner): {@code Concluida},
     * {@code JaTerminalIdentica} e {@code JaTerminalContraditoria} terminalizam porque a solicitacao
     * ESTA efetivamente terminal; {@code SucessoIncoerente} e {@code ProtocoloDivergente} NUNCA
     * terminalizam -- o Core respondeu, mas o caso de uso recusou aplicar, entao a solicitacao
     * permanece nao-terminal e a reconciliacao permanece {@code PENDENTE}.
     */
    private ResultadoCicloReconciliacao aplicarResultadoAutoritativo(
            EfetivacaoReconciliacaoReclamada claim, ResultadoEfetivacaoRecebido resultado,
            Optional<ProtocoloCore> protocoloParaRegistrar, Instant agora) {

        ResultadoRegistroEfetivacao registro = registrarResultadoEfetivacao.executar(
                claim.efetivacaoId(), resultado, protocoloParaRegistrar, AtorSistema.RECONCILIACAO_EFETIVACAO, agora);

        return switch (registro) {
            case ResultadoRegistroEfetivacao.Concluida concluida -> {
                reconciliacao.terminalizar(claim, agora);
                yield new ResultadoCicloReconciliacao.ConcluidaPorResultadoAutoritativo(concluida.statusResultante());
            }
            case ResultadoRegistroEfetivacao.JaTerminalIdentica identica -> {
                reconciliacao.terminalizar(claim, agora);
                yield new ResultadoCicloReconciliacao.ConcluidaPorOutroCaminho(identica.statusPersistido(), false);
            }
            case ResultadoRegistroEfetivacao.JaTerminalContraditoria contraditoria -> {
                reconciliacao.terminalizar(claim, agora);
                yield new ResultadoCicloReconciliacao.ConcluidaPorOutroCaminho(contraditoria.statusPersistido(), true);
            }
            case ResultadoRegistroEfetivacao.SucessoIncoerente ignored -> reagendarOuIndeterminar(claim, agora, true);
            case ResultadoRegistroEfetivacao.ProtocoloDivergente ignored -> reagendarOuIndeterminar(claim, agora, true);
        };
    }

    /**
     * Ja indeterminada: polling de baixa frequencia continua com o backoff LONGO, sem consultar a
     * janela novamente -- a conclusao tardia por conclusao concorrente e capturada no PROXIMO
     * claim (TX-A junta com {@code solicitacao_aumento_limite} e terminaliza sem HTTP quando ja
     * terminal), nao aqui. Ainda dentro da janela: backoff CURTO (exponencial, mesma
     * {@link PoliticaRetryEntrega} do dispatcher, instanciada com config propria). Janela esgotada
     * agora: converge em {@link RegistrarResultadoEfetivacao#registrarIndeterminacao}.
     */
    private ResultadoCicloReconciliacao reagendarOuIndeterminar(
            EfetivacaoReconciliacaoReclamada claim, Instant agora, boolean incoerente) {

        if (claim.jaIndeterminada()) {
            reconciliacao.reagendarAposIndeterminacao(claim, agora.plus(backoffLongo), agora);
            return new ResultadoCicloReconciliacao.JaEstavaIndeterminada(incoerente);
        }

        if (agora.isBefore(claim.janelaExpiraEm())) {
            Instant proximaConsulta = agora.plus(politicaRetryConsulta.calcularEspera(claim.tentativaAtual()));
            reconciliacao.reagendar(claim, proximaConsulta, agora);
            return incoerente
                    ? new ResultadoCicloReconciliacao.ReagendadaPorResultadoIncoerente()
                    : new ResultadoCicloReconciliacao.ReagendadaSemResultadoAutoritativo();
        }

        ResultadoIndeterminacao indeterminacao = registrarResultadoEfetivacao.registrarIndeterminacao(claim.efetivacaoId(), agora);
        return switch (indeterminacao) {
            case ResultadoIndeterminacao.IndeterminadaAgora ignored -> {
                reconciliacao.reagendarAposIndeterminacao(claim, agora.plus(backoffLongo), agora);
                yield new ResultadoCicloReconciliacao.IndeterminadaAgora();
            }
            case ResultadoIndeterminacao.JaEstavaIndeterminada ignored -> {
                reconciliacao.reagendarAposIndeterminacao(claim, agora.plus(backoffLongo), agora);
                yield new ResultadoCicloReconciliacao.JaEstavaIndeterminada(incoerente);
            }
            case ResultadoIndeterminacao.JaTerminal jaTerminal -> {
                reconciliacao.terminalizar(claim, agora);
                yield new ResultadoCicloReconciliacao.ConcluidaPorOutroCaminho(jaTerminal.statusPersistido(), false);
            }
        };
    }
}
