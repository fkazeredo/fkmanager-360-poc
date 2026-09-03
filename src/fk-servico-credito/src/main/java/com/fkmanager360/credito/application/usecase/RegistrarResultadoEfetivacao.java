package com.fkmanager360.credito.application.usecase;

import com.fkmanager360.credito.application.port.out.ResultadoEfetivacaoPort;
import com.fkmanager360.credito.application.port.out.ResultadoEfetivacaoRecebido;
import com.fkmanager360.credito.application.port.out.ResultadoRegistroEfetivacao;
import com.fkmanager360.credito.domain.AtorOperacao;
import com.fkmanager360.credito.domain.EfetivacaoId;

import java.time.Instant;
import java.util.Objects;

/**
 * Caso de uso UNICO de conclusao da efetivacao (ADR-0009; ticket #0004, Objetivo): "uma recusa
 * definitiva ja no aceite precisa concluir a solicitacao. Esse caso de uso e unico: #0005 e #0006
 * acrescentam entradas para ele, nunca uma segunda implementacao da regra de conclusao." Hoje so e
 * alcancado pelo dispatcher (via {@code EntregasEfetivacaoPort.concluirComFalhaDefinitiva}, que o
 * invoca dentro da mesma transacao de fencing); #0005 acrescenta o callback como segunda entrada,
 * #0006 a reconciliacao como terceira -- nenhuma delas duplica {@link ResultadoEfetivacaoPort}.
 *
 * <p>Idempotente por construcao: a regra "ja terminal? nao reescreve" vive inteiramente no adapter
 * de persistencia (mesmo padrao de {@code aplicarDecisaoTx2}/TX2, #0003), nao aqui -- este caso de
 * uso e so orquestracao.
 */
public class RegistrarResultadoEfetivacao {

    private final ResultadoEfetivacaoPort resultadoEfetivacao;

    public RegistrarResultadoEfetivacao(ResultadoEfetivacaoPort resultadoEfetivacao) {
        this.resultadoEfetivacao = Objects.requireNonNull(resultadoEfetivacao, "resultadoEfetivacao e obrigatorio");
    }

    public ResultadoRegistroEfetivacao executar(
            EfetivacaoId efetivacaoId, ResultadoEfetivacaoRecebido resultado, AtorOperacao autor, Instant agora) {
        Objects.requireNonNull(efetivacaoId, "efetivacaoId e obrigatorio");
        Objects.requireNonNull(resultado, "resultado e obrigatorio");
        Objects.requireNonNull(autor, "autor e obrigatorio");
        Objects.requireNonNull(agora, "agora e obrigatorio");
        return resultadoEfetivacao.registrar(efetivacaoId, resultado, autor, agora);
    }
}
