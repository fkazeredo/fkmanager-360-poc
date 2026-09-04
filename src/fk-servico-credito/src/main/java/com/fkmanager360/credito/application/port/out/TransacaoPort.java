package com.fkmanager360.credito.application.port.out;

import java.util.function.Supplier;

/**
 * Unidade de trabalho transacional exposta a aplicacao (decisao do Owner, revisao de 2026-09-04):
 * existe EXCLUSIVAMENTE para a composicao fenced da conclusao definitiva -- verificacao de claim,
 * conclusao via {@link ResultadoEfetivacaoPort} e terminalizacao da entrega precisam ser um unico
 * commit, e a orquestracao dessa sequencia pertence ao caso de uso {@code
 * RegistrarResultadoEfetivacao} (ADR-0009), que nao pode depender de Spring (ArchUnit).
 *
 * <p><b>Nao e um convite a orquestrar transacoes genericas na aplicacao</b> (ADR-0010): o idioma
 * padrao deste modulo continua sendo "um metodo de porta = uma transacao" (ADR-0023), e as
 * rejeicoes documentadas de {@code TransactionTemplate} em TX1/TX2 ({@code
 * JpaSolicitacoesAumentoLimiteAdapter}, {@code CreditoPersistenceOperations}) permanecem validas
 * para aqueles fluxos. Um segundo uso desta porta exige a mesma justificativa que este primeiro:
 * uma sequencia de portas que a spec obriga a ser atomica E cuja regra de composicao e da
 * aplicacao, nao de um adapter.
 *
 * <p>Uma excecao lancada pela {@code unidade} atravessa e desfaz a transacao inteira.
 */
public interface TransacaoPort {

    <T> T executar(Supplier<T> unidade);
}
