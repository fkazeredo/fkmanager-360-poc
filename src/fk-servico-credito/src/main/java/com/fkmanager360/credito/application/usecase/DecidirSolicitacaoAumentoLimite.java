package com.fkmanager360.credito.application.usecase;

import com.fkmanager360.credito.application.ResultadoSubmissao;
import com.fkmanager360.credito.application.port.out.CargaParaDecisao;
import com.fkmanager360.credito.application.port.out.EntradaHistorico;
import com.fkmanager360.credito.application.port.out.IntencaoEfetivacao;
import com.fkmanager360.credito.application.port.out.ResultadoAplicacaoDecisao;
import com.fkmanager360.credito.application.port.out.SolicitacoesAumentoLimitePort;
import com.fkmanager360.credito.application.port.out.TipoFatoHistorico;
import com.fkmanager360.credito.domain.AtorSistema;
import com.fkmanager360.credito.domain.DecisaoCredito;
import com.fkmanager360.credito.domain.EfetivacaoId;
import com.fkmanager360.credito.domain.MotorDecisaoCredito;
import com.fkmanager360.credito.domain.ResultadoDecisaoCredito;
import com.fkmanager360.credito.domain.SolicitacaoId;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Fase 2 (decisao, fora de transacao) + Fase 3 (TX2) do plano #0003. E o caso de uso que garante a
 * RETOMADA de uma solicitacao {@code SOLICITADA} sem nenhuma chamada remota: so usa
 * {@link SolicitacoesAumentoLimitePort} e {@link MotorDecisaoCredito}, nada mais -- nenhum campo,
 * nenhuma dependencia deste tipo referencia {@code DireitoDeAtendimentoPort} nem
 * {@code DadosCreditoCorePort} (AC18).
 *
 * <p><b>Guardrail critico (D5 do plano):</b> a decisao SEMPRE usa
 * {@code contexto.versaoPoliticaCredito()} -- nunca a vigente do motor -- porque
 * {@link MotorDecisaoCredito#decidir} ja resolve assim internamente. E isso que faz a retomada
 * respeitar a politica congelada mesmo que a vigente tenha mudado entre a captura do contexto e a
 * retomada.
 *
 * <p><b>Por que nao ha um {@code if (status != SOLICITADA) return replay}</b> antes de chamar
 * {@link MotorDecisaoCredito#decidir}: decidir e uma funcao pura sem efeito colateral, e chama-la
 * de novo sobre o mesmo contexto congelado sempre produz a mesma decisao (AC33) -- o calculo e
 * descartado pelo adapter quando nao ha nada a escrever. A UNICA fonte de verdade sobre "isto e
 * uma escrita nova ou um replay" e o retorno atomico e sob lock de
 * {@link SolicitacoesAumentoLimitePort#aplicarDecisao}: e ele quem decide, com
 * {@code decidiuAgora}, se algo foi de fato persistido agora. Isso e o que impede duas
 * implementacoes divergentes da mesma regra de idempotencia (replay de submissao e retomada de
 * SOLICITADA convergem exatamente aqui).
 */
public class DecidirSolicitacaoAumentoLimite {

    private final SolicitacoesAumentoLimitePort solicitacoes;
    private final MotorDecisaoCredito motorDecisaoCredito;

    public DecidirSolicitacaoAumentoLimite(
            SolicitacoesAumentoLimitePort solicitacoes, MotorDecisaoCredito motorDecisaoCredito) {
        this.solicitacoes = solicitacoes;
        this.motorDecisaoCredito = motorDecisaoCredito;
    }

    public ResultadoSubmissao executar(SolicitacaoId id, Instant decididaEm) {
        Objects.requireNonNull(id, "id e obrigatorio");
        Objects.requireNonNull(decididaEm, "decididaEm e obrigatorio");

        CargaParaDecisao carga = solicitacoes.carregarParaDecisao(id);

        DecisaoCredito decisaoCalculada = motorDecisaoCredito.decidir(carga.contexto(), decididaEm);

        IntencaoEfetivacao intencao = null;
        if (decisaoCalculada.resultado() == ResultadoDecisaoCredito.APROVADA) {
            intencao = new IntencaoEfetivacao(
                    new EfetivacaoId(UUID.randomUUID()),
                    UUID.randomUUID(),
                    carga.contaId(),
                    carga.contexto().dadosCreditoCore().limiteChequeEspecialVigente(),
                    carga.contexto().limiteSolicitado(),
                    carga.correlationId());
        }

        // fatoId deterministico a partir da solicitacaoId: reexecutar esta fase sobre a mesma
        // solicitacao (replay/retomada) nunca produz uma segunda entrada de historico -- a UNIQUE
        // constraint em fato_id (proxima etapa) e a garantia fisica, mas o valor precisa ser
        // deterministico para ela funcionar.
        EntradaHistorico entrada = new EntradaHistorico(
                "DECISAO:" + id.valor(),
                TipoFatoHistorico.DECISAO_AUTOMATICA_REGISTRADA,
                AtorSistema.MOTOR_DECISAO_CREDITO,
                decididaEm);

        ResultadoAplicacaoDecisao resultadoAplicacao =
                solicitacoes.aplicarDecisao(id, decisaoCalculada, intencao, entrada);

        return new ResultadoSubmissao(
                id,
                carga.contaId(),
                resultadoAplicacao.statusResultante(),
                carga.contexto().dadosCreditoCore().limiteChequeEspecialVigente(),
                carga.contexto().limiteSolicitado(),
                resultadoAplicacao.decisaoVigente(),
                false,
                resultadoAplicacao.decidiuAgora());
    }
}
