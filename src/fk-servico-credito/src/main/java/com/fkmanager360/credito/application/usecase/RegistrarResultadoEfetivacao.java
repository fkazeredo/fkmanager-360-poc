package com.fkmanager360.credito.application.usecase;

import com.fkmanager360.credito.application.port.out.EntregaEfetivacaoReclamada;
import com.fkmanager360.credito.application.port.out.EntregasEfetivacaoPort;
import com.fkmanager360.credito.application.port.out.ResultadoConclusaoDefinitiva;
import com.fkmanager360.credito.application.port.out.ResultadoEfetivacaoPort;
import com.fkmanager360.credito.application.port.out.ResultadoEfetivacaoRecebido;
import com.fkmanager360.credito.application.port.out.ResultadoIndeterminacao;
import com.fkmanager360.credito.application.port.out.ResultadoRegistroEfetivacao;
import com.fkmanager360.credito.application.port.out.TransacaoPort;
import com.fkmanager360.credito.domain.AtorOperacao;
import com.fkmanager360.credito.domain.EfetivacaoId;
import com.fkmanager360.credito.domain.ProtocoloCore;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Caso de uso UNICO de conclusao da efetivacao (ADR-0009; ticket #0004, Objetivo): "uma recusa
 * definitiva ja no aceite precisa concluir a solicitacao. Esse caso de uso e unico: #0005 e #0006
 * acrescentam entradas para ele, nunca uma segunda implementacao da regra de conclusao." A entrada
 * do dispatcher e {@link #executarSobClaim}; o callback de #0005 entra por {@link #executar};
 * a reconciliacao de #0006 tambem entra por {@link #executar} quando o Core responde de forma
 * autoritativa, e por {@link #registrarIndeterminacao} quando a janela normal se esgota sem
 * resposta -- nenhuma delas duplica {@link ResultadoEfetivacaoPort}.
 *
 * <p><b>{@link #executarSobClaim} e a composicao fenced</b> (revisao do Owner, 2026-09-04): dentro
 * de uma unica {@link TransacaoPort}, verifica o claim ({@code SELECT ... FOR UPDATE} no adapter),
 * conclui pela porta de resultado e terminaliza a entrega -- claim obsoleto descarta tudo sem
 * nenhuma escrita. E a aplicacao quem dita essa sequencia; os adapters apenas executam cada passo.
 *
 * <p><b>Conclusao concorrente (#0005, guardrail normativo do Owner).</b> Quando o callback ja
 * terminalizou a solicitacao antes desta chamada sob claim aplicar o resultado do dispatcher, o
 * terminal PERSISTIDO e autoritativo: o resultado que o dispatcher trazia perde autoridade de
 * escrita por inteiro, e a entrega termina tecnicamente de acordo com o terminal observado --
 * nunca com o resultado perdedor. Ver {@link ResultadoConclusaoDefinitiva.ConcluidaPorOutroCaminho}.
 *
 * <p>Idempotente por construcao: a regra "ja terminal? nao reescreve" vive inteiramente no adapter
 * de persistencia (mesmo padrao de {@code aplicarDecisaoTx2}/TX2, #0003), nao aqui -- este caso de
 * uso e so orquestracao.
 */
public class RegistrarResultadoEfetivacao {

    private final ResultadoEfetivacaoPort resultadoEfetivacao;
    private final EntregasEfetivacaoPort entregas;
    private final TransacaoPort transacao;

    public RegistrarResultadoEfetivacao(
            ResultadoEfetivacaoPort resultadoEfetivacao, EntregasEfetivacaoPort entregas, TransacaoPort transacao) {
        this.resultadoEfetivacao = Objects.requireNonNull(resultadoEfetivacao, "resultadoEfetivacao e obrigatorio");
        this.entregas = Objects.requireNonNull(entregas, "entregas e obrigatorio");
        this.transacao = Objects.requireNonNull(transacao, "transacao e obrigatoria");
    }

    /**
     * Entrada sem claim (resultado autoritativo direto -- callback de #0005, reconciliacao de
     * #0006). {@code protocoloInformado} carrega o {@code ProtocoloCore} quando o chamador o
     * conhece (o callback sempre o traz -- ver Javadoc de {@link ResultadoEfetivacaoPort}).
     */
    public ResultadoRegistroEfetivacao executar(
            EfetivacaoId efetivacaoId, ResultadoEfetivacaoRecebido resultado, Optional<ProtocoloCore> protocoloInformado,
            AtorOperacao autor, Instant agora) {
        Objects.requireNonNull(efetivacaoId, "efetivacaoId e obrigatorio");
        Objects.requireNonNull(resultado, "resultado e obrigatorio");
        Objects.requireNonNull(protocoloInformado, "protocoloInformado e obrigatorio");
        Objects.requireNonNull(autor, "autor e obrigatorio");
        Objects.requireNonNull(agora, "agora e obrigatorio");
        return resultadoEfetivacao.registrar(efetivacaoId, resultado, protocoloInformado, autor, agora);
    }

    /**
     * Janela normal de recuperacao automatica esgotada sem resultado autoritativo (#0006, AC16):
     * a UNICA saida NAO autoritativa de {@code AGUARDANDO_EFETIVACAO} -- nunca produz
     * {@code FALHA_EFETIVACAO}. Chamada de DENTRO da mesma {@link TransacaoPort} que o
     * reconciliador ja abriu para a convergencia do ciclo (fencing + consulta ja resolvida fora da
     * TX + esta transicao + bookkeeping da propria reconciliacao), nunca standalone -- e essa
     * unidade unica que garante que a transicao de negocio e o reagendamento nunca ficam em
     * commits independentes (guardrail normativo do Owner, #0006).
     */
    public ResultadoIndeterminacao registrarIndeterminacao(EfetivacaoId efetivacaoId, Instant agora) {
        Objects.requireNonNull(efetivacaoId, "efetivacaoId e obrigatorio");
        Objects.requireNonNull(agora, "agora e obrigatorio");
        return resultadoEfetivacao.registrarIndeterminacao(efetivacaoId, agora);
    }

    /**
     * Entrada do dispatcher: conclusao fenced pelo claim, atomica de ponta a ponta -- UMA unica
     * unidade transacional (nunca duas transacoes sucessivas): lock de {@code outbox_entrega}
     * (fencing), lock de {@code solicitacao_aumento_limite} (dentro de
     * {@link ResultadoEfetivacaoPort#registrar}), terminalizacao da entrega e commit unico. Ordem
     * global de locks preservada em toda a plataforma: {@code outbox_entrega} sempre antes de
     * {@code solicitacao_aumento_limite} -- o callback puro ({@link #executar}) nunca toma o lock
     * de {@code outbox_entrega}, entao as duas vias nao podem se dar-lock mutuamente (sem
     * deadlock). O dispatcher nunca informa {@code ProtocoloCore} nesta via -- aprender protocolo
     * continua exclusivo de {@code registrarAceite}.
     */
    public ResultadoConclusaoDefinitiva executarSobClaim(
            EntregaEfetivacaoReclamada claim, ResultadoEfetivacaoRecebido resultado, AtorOperacao autor, Instant agora) {
        Objects.requireNonNull(claim, "claim e obrigatorio");
        Objects.requireNonNull(resultado, "resultado e obrigatorio");
        Objects.requireNonNull(autor, "autor e obrigatorio");
        Objects.requireNonNull(agora, "agora e obrigatorio");

        return transacao.executar(() -> {
            if (!entregas.claimAindaValido(claim)) {
                return new ResultadoConclusaoDefinitiva.DescartadoClaimObsoleto();
            }

            ResultadoRegistroEfetivacao conclusao = resultadoEfetivacao.registrar(
                    claim.intencao().efetivacaoId(), resultado, Optional.empty(), autor, agora);

            return switch (conclusao) {
                case ResultadoRegistroEfetivacao.Concluida concluida -> {
                    entregas.terminalizarPorFalhaDefinitiva(claim, agora);
                    yield new ResultadoConclusaoDefinitiva.Aplicado(concluida.permanenciaEmAguardandoEfetivacao());
                }
                case ResultadoRegistroEfetivacao.JaTerminalIdentica identica -> {
                    entregas.terminalizarPorConclusaoConcorrente(claim, identica.statusPersistido(), agora);
                    yield new ResultadoConclusaoDefinitiva.ConcluidaPorOutroCaminho(identica.statusPersistido(), false);
                }
                case ResultadoRegistroEfetivacao.JaTerminalContraditoria contraditoria -> {
                    entregas.terminalizarPorConclusaoConcorrente(claim, contraditoria.statusPersistido(), agora);
                    yield new ResultadoConclusaoDefinitiva.ConcluidaPorOutroCaminho(contraditoria.statusPersistido(), true);
                }
                case ResultadoRegistroEfetivacao.SucessoIncoerente ignored -> throw new IllegalStateException(
                        "executarSobClaim: SucessoIncoerente e inalcancavel aqui -- o dispatcher so registra "
                                + "FalhaDefinitiva nesta via, nunca Sucesso -- invariante do #0005 quebrada");
                case ResultadoRegistroEfetivacao.ProtocoloDivergente ignored -> throw new IllegalStateException(
                        "executarSobClaim: ProtocoloDivergente e inalcancavel aqui -- o dispatcher nunca informa "
                                + "protocolo nesta via -- invariante do #0005 quebrada");
            };
        });
    }
}
