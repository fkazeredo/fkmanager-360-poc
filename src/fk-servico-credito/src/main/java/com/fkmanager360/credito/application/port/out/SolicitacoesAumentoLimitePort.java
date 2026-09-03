package com.fkmanager360.credito.application.port.out;

import com.fkmanager360.credito.domain.DecisaoCredito;
import com.fkmanager360.credito.domain.SolicitacaoId;

/**
 * Porta de saida para a persistencia atomica da SolicitacaoAumentoLimite (plano #0003, decisao
 * estruturante D6): o adapter que implementa esta porta e puramente mecanico. Recebe uma
 * {@link DecisaoCredito} JA CALCULADA em {@link #aplicarDecisao}, nao conhece
 * {@code MotorDecisaoCredito} nem {@code PoliticaCredito}, e nao executa regra de negocio alguma
 * -- a proxima etapa (persistencia) tem uma regra ArchUnit dedicada a isso.
 *
 * <p><b>Guardrail de concorrencia -- leia com atencao antes de implementar o adapter (corrige um
 * blocker de review do plano deste ticket).</b> O indice de unicidade nao terminal por conta
 * ({@code uk_solicitacao_nao_terminal_por_conta}) e atingido pelo PRIMEIRO insert de TX1, antes da
 * PK de idempotencia ({@code pk_registro_idempotencia}, o TERCEIRO insert). Isso significa que duas
 * requisicoes com a MESMA {@code Idempotency-Key} e o MESMO fingerprint para a MESMA conta,
 * competindo de verdade, fazem a perdedora colidir no indice de conta -- nao na PK de idempotencia
 * -- mesmo sendo, semanticamente, um caso de idempotencia (replay ou "em processamento"), e nunca
 * uma segunda solicitacao concorrente de verdade.
 *
 * <p>Por isso, em QUALQUER conflito de TX1 -- nao importa qual das duas constraints foi atingida
 * --, a implementacao desta porta DEVE reler o registro de idempotencia
 * ({@code originadorId}, {@code key}) depois do rollback e:
 * <ol>
 *   <li>se o registro EXISTE -&gt; devolver {@link RegistroIdempotenteEncontrado}, para que a
 *       aplicacao classifique com {@code com.fkmanager360.credito.application.ClassificadorIdempotencia}
 *       -- a MESMA funcao usada no pre-check da Fase 0 (passo 5);</li>
 *   <li>se NAO existe -&gt; so entao devolver {@link SolicitacaoNaoTerminalExistente}.</li>
 * </ol>
 * A distincao por statement continua util para diagnostico e log, mas NUNCA e, sozinha, o que
 * decide a resposta ao gerente -- a releitura e quem decide.
 *
 * <p>{@link #aplicarDecisao} (TX2) deve, atomicamente e sob lock, checar se a solicitacao ainda
 * esta {@code SOLICITADA}: se estiver, gravar a decisao recem-calculada, transicionar o status e
 * gravar a intencao de efetivacao (se aprovada) e a entrada de historico, devolvendo
 * {@code decidiuAgora=true}; caso contrario (ja decidida, ou lock indisponivel por concorrencia --
 * traduzido para {@code IdempotenciaEmProcessamentoException}), devolver o que ja esta persistido
 * com {@code decidiuAgora=false}, sem reescrever nada. E este contrato -- e nao um `if` no caso de
 * uso -- que garante replay/retomada nunca duplicarem decisao ou historico.
 */
public interface SolicitacoesAumentoLimitePort {

    ResultadoRegistroSolicitacao registrar(NovaSolicitacaoAumentoLimite dados);

    CargaParaDecisao carregarParaDecisao(SolicitacaoId id);

    ResultadoAplicacaoDecisao aplicarDecisao(
            SolicitacaoId id,
            DecisaoCredito decisao,
            IntencaoEfetivacao intencaoOuNull,
            EntradaHistorico entrada);
}
