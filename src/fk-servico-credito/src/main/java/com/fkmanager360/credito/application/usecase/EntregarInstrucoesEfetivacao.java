package com.fkmanager360.credito.application.usecase;

import com.fkmanager360.credito.application.PoliticaRetryEntrega;
import com.fkmanager360.credito.application.ResultadoEpisodioEntrega;
import com.fkmanager360.credito.application.port.out.EntregaEfetivacaoReclamada;
import com.fkmanager360.credito.application.port.out.EntregasEfetivacaoPort;
import com.fkmanager360.credito.application.port.out.InstrucaoEfetivacaoCorePort;
import com.fkmanager360.credito.application.port.out.ReclamacaoEntrega;
import com.fkmanager360.credito.application.port.out.ResultadoConclusaoDefinitiva;
import com.fkmanager360.credito.application.port.out.ResultadoInstrucaoCore;
import com.fkmanager360.credito.application.port.out.ResultadoRegistroEntrega;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * O dispatcher de efetivacao (spec, secao "Dispatcher"; plano #0004, secoes 1 e 9): entrega
 * exatamente UM episodio por chamada -- reclama a proxima entrega devida, chama o Core e persiste
 * o desfecho. O loop de "ate {@code credito.efetivacao.entrega.lote} episodios por tick" e o
 * paralelismo entre instancias (via {@code SKIP LOCKED}) pertencem ao adapter de agendamento
 * (decisao do Owner, OD-1) -- nunca a este caso de uso, que permanece Java puro e testavel em S2
 * com um fake de {@link InstrucaoEfetivacaoCorePort} (ADR-0018).
 *
 * <p>As quatro classes de {@link ResultadoInstrucaoCore} sao tratadas exaustivamente por
 * {@code sealed}+{@code switch}: o compilador falha se uma nova classe for introduzida sem que
 * este caso de uso decida o que fazer com ela. O fencing e resolvido inteiramente dentro de
 * {@link EntregasEfetivacaoPort} -- este caso de uso so reage a
 * {@link ResultadoRegistroEntrega#DESCARTADO_CLAIM_OBSOLETO} devolvendo
 * {@link ResultadoEpisodioEntrega.DescartadaPorFencing}, sem tentar reinterpretar a decisao.
 *
 * <p><b>Dois instantes, nao um.</b> O {@link Clock} e consultado antes do claim E de novo depois
 * do retorno do Core: a chamada HTTP pode levar segundos (timeout de leitura configurado em
 * {@code coreLegadoRestClient}), e reusar o instante pre-HTTP para calcular
 * {@code proximaTentativaEm} ou a permanencia em AGUARDANDO_EFETIVACAO subtrairia essa latencia do
 * backoff e subestimaria a metrica -- um instante capturado antes do envio pode ja estar no
 * passado quando TX-B commita.
 */
public class EntregarInstrucoesEfetivacao {

    private final EntregasEfetivacaoPort entregas;
    private final InstrucaoEfetivacaoCorePort core;
    private final PoliticaRetryEntrega politicaRetry;
    private final Clock clock;
    private final int maxTentativas;
    private final Duration lease;

    public EntregarInstrucoesEfetivacao(
            EntregasEfetivacaoPort entregas,
            InstrucaoEfetivacaoCorePort core,
            PoliticaRetryEntrega politicaRetry,
            Clock clock,
            int maxTentativas,
            Duration lease) {
        this.entregas = Objects.requireNonNull(entregas, "entregas e obrigatorio");
        this.core = Objects.requireNonNull(core, "core e obrigatorio");
        this.politicaRetry = Objects.requireNonNull(politicaRetry, "politicaRetry e obrigatoria");
        this.clock = Objects.requireNonNull(clock, "clock e obrigatorio");
        if (maxTentativas < 1) {
            throw new IllegalArgumentException("maxTentativas deve ser >= 1: " + maxTentativas);
        }
        this.maxTentativas = maxTentativas;
        this.lease = Objects.requireNonNull(lease, "lease e obrigatorio");
    }

    public ResultadoEpisodioEntrega executarUmEpisodio() {
        ReclamacaoEntrega reclamacao = entregas.reclamarProxima(clock.instant(), maxTentativas, lease);

        if (reclamacao instanceof ReclamacaoEntrega.NenhumaPendente) {
            return new ResultadoEpisodioEntrega.SemPendente();
        }
        if (reclamacao instanceof ReclamacaoEntrega.EsgotadaAgora) {
            return new ResultadoEpisodioEntrega.EsgotadaAgora();
        }

        EntregaEfetivacaoReclamada claim = ((ReclamacaoEntrega.Reclamada) reclamacao).entrega();
        ResultadoInstrucaoCore resultado = core.entregar(claim.intencao());
        Instant agoraPosHttp = clock.instant();

        return switch (resultado) {
            case ResultadoInstrucaoCore.Aceite aceite -> aplicarAceite(claim, aceite, agoraPosHttp);
            case ResultadoInstrucaoCore.FalhaTransitoria transitoria -> aplicarTransitoria(claim, transitoria, agoraPosHttp);
            case ResultadoInstrucaoCore.FalhaDefinitiva definitiva -> aplicarDefinitiva(claim, definitiva, agoraPosHttp);
            case ResultadoInstrucaoCore.RespostaIndeterminada indeterminada -> aplicarIndeterminada(claim, indeterminada, agoraPosHttp);
        };
    }

    private ResultadoEpisodioEntrega aplicarAceite(
            EntregaEfetivacaoReclamada claim, ResultadoInstrucaoCore.Aceite aceite, Instant agora) {
        ResultadoRegistroEntrega r = entregas.registrarAceite(claim, aceite.protocoloCore(), agora);
        return switch (r) {
            case APLICADO -> new ResultadoEpisodioEntrega.Aceite();
            case APLICADO_COM_ANOMALIA_PROTOCOLO_DIVERGENTE -> new ResultadoEpisodioEntrega.AceiteComAnomaliaProtocoloDivergente();
            case DESCARTADO_CLAIM_OBSOLETO -> new ResultadoEpisodioEntrega.DescartadaPorFencing();
        };
    }

    private ResultadoEpisodioEntrega aplicarTransitoria(
            EntregaEfetivacaoReclamada claim, ResultadoInstrucaoCore.FalhaTransitoria transitoria, Instant agora) {
        Instant proximaTentativaEm = agora.plus(politicaRetry.calcularEspera(claim.tentativaAtual()));
        ResultadoRegistroEntrega r = entregas.reagendar(claim, proximaTentativaEm, transitoria.detalheTecnico(), agora);
        return switch (r) {
            case APLICADO, APLICADO_COM_ANOMALIA_PROTOCOLO_DIVERGENTE -> new ResultadoEpisodioEntrega.Reagendada();
            case DESCARTADO_CLAIM_OBSOLETO -> new ResultadoEpisodioEntrega.DescartadaPorFencing();
        };
    }

    private ResultadoEpisodioEntrega aplicarDefinitiva(
            EntregaEfetivacaoReclamada claim, ResultadoInstrucaoCore.FalhaDefinitiva definitiva, Instant agora) {
        ResultadoConclusaoDefinitiva r = entregas.concluirComFalhaDefinitiva(claim, definitiva.motivo(), agora);
        return switch (r) {
            case ResultadoConclusaoDefinitiva.Aplicado aplicado ->
                    new ResultadoEpisodioEntrega.FalhaDefinitiva(definitiva.motivo(), aplicado.permanenciaEmAguardandoEfetivacao());
            case ResultadoConclusaoDefinitiva.DescartadoClaimObsoleto ignored ->
                    new ResultadoEpisodioEntrega.DescartadaPorFencing();
        };
    }

    private ResultadoEpisodioEntrega aplicarIndeterminada(
            EntregaEfetivacaoReclamada claim, ResultadoInstrucaoCore.RespostaIndeterminada indeterminada, Instant agora) {
        ResultadoRegistroEntrega r = entregas.marcarIndeterminada(claim, indeterminada.detalheTecnico(), agora);
        return switch (r) {
            case APLICADO, APLICADO_COM_ANOMALIA_PROTOCOLO_DIVERGENTE -> new ResultadoEpisodioEntrega.Indeterminada();
            case DESCARTADO_CLAIM_OBSOLETO -> new ResultadoEpisodioEntrega.DescartadaPorFencing();
        };
    }
}
