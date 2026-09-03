package com.fkmanager360.credito.application.port.out;

/**
 * Resultado selado de TX1 ({@link SolicitacoesAumentoLimitePort#registrar}).
 * {@link RegistroIdempotenteEncontrado} e reaproveitado tanto no pre-check (Fase 0, passo 5)
 * quanto no caminho de conflito de TX1 (ver o guardrail de concorrencia documentado na porta) --
 * a aplicacao classifica o registro pela MESMA funcao
 * ({@code com.fkmanager360.credito.application.ClassificadorIdempotencia}) nao importa por qual
 * caminho o resultado chegou.
 */
public sealed interface ResultadoRegistroSolicitacao
        permits SolicitacaoCriada, RegistroIdempotenteEncontrado, SolicitacaoNaoTerminalExistente {
}
