package com.fkmanager360.credito.application.usecase;

import com.fkmanager360.credito.application.port.out.EntregaEfetivacaoReclamada;
import com.fkmanager360.credito.application.port.out.EntregasEfetivacaoPort;
import com.fkmanager360.credito.application.port.out.ResultadoConclusaoDefinitiva;
import com.fkmanager360.credito.application.port.out.ResultadoEfetivacaoPort;
import com.fkmanager360.credito.application.port.out.ResultadoEfetivacaoRecebido;
import com.fkmanager360.credito.application.port.out.ResultadoRegistroEfetivacao;
import com.fkmanager360.credito.application.port.out.TransacaoPort;
import com.fkmanager360.credito.domain.AtorOperacao;
import com.fkmanager360.credito.domain.EfetivacaoId;

import java.time.Instant;
import java.util.Objects;

/**
 * Caso de uso UNICO de conclusao da efetivacao (ADR-0009; ticket #0004, Objetivo): "uma recusa
 * definitiva ja no aceite precisa concluir a solicitacao. Esse caso de uso e unico: #0005 e #0006
 * acrescentam entradas para ele, nunca uma segunda implementacao da regra de conclusao." A entrada
 * do dispatcher e {@link #executarSobClaim}; #0005 acrescenta o callback (via {@link #executar}),
 * #0006 a reconciliacao -- nenhuma delas duplica {@link ResultadoEfetivacaoPort}.
 *
 * <p><b>{@link #executarSobClaim} e a composicao fenced</b> (revisao do Owner, 2026-09-04): dentro
 * de uma unica {@link TransacaoPort}, verifica o claim ({@code SELECT ... FOR UPDATE} no adapter),
 * conclui pela porta de resultado e terminaliza a entrega -- claim obsoleto descarta tudo sem
 * nenhuma escrita. E a aplicacao quem dita essa sequencia; os adapters apenas executam cada passo.
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

    /** Entrada sem claim (resultado autoritativo direto -- callback de #0005, reconciliacao de #0006). */
    public ResultadoRegistroEfetivacao executar(
            EfetivacaoId efetivacaoId, ResultadoEfetivacaoRecebido resultado, AtorOperacao autor, Instant agora) {
        Objects.requireNonNull(efetivacaoId, "efetivacaoId e obrigatorio");
        Objects.requireNonNull(resultado, "resultado e obrigatorio");
        Objects.requireNonNull(autor, "autor e obrigatorio");
        Objects.requireNonNull(agora, "agora e obrigatorio");
        return resultadoEfetivacao.registrar(efetivacaoId, resultado, autor, agora);
    }

    /**
     * Entrada do dispatcher: conclusao fenced pelo claim, atomica de ponta a ponta. O guard de
     * {@code concluiuAgora} e invariante do #0004 -- com o fencing valido, nenhum outro caminho
     * deste ticket alcanca a solicitacao antes; quando #0005/#0006 introduzirem caminhos
     * concorrentes legitimos, este guard deve virar uma variante propria de
     * {@link ResultadoConclusaoDefinitiva} (ex.: "ja concluida por outro caminho"), nunca um
     * aceite silencioso. A excecao desfaz a transacao inteira.
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

            ResultadoRegistroEfetivacao conclusao =
                    resultadoEfetivacao.registrar(claim.intencao().efetivacaoId(), resultado, autor, agora);

            entregas.terminalizarPorFalhaDefinitiva(claim, agora);

            if (!conclusao.concluiuAgora()) {
                throw new IllegalStateException(
                        "executarSobClaim: conclusao nao aconteceu agora para efetivacaoId="
                                + claim.intencao().efetivacaoId() + " -- invariante do #0004 quebrada (ver Javadoc)");
            }
            return new ResultadoConclusaoDefinitiva.Aplicado(conclusao.permanenciaEmAguardandoEfetivacao());
        });
    }
}
