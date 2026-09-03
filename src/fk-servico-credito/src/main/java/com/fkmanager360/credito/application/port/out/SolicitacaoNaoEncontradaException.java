package com.fkmanager360.credito.application.port.out;

/**
 * Uma SolicitacaoAumentoLimite referenciada por {@code SolicitacaoId} nao foi encontrada no
 * armazenamento (proxima etapa, persistencia). Nao deveria acontecer no fluxo normal -- o
 * {@code SolicitacaoId} usado por {@link SolicitacoesAumentoLimitePort#carregarParaDecisao} e por
 * {@link SolicitacoesAumentoLimitePort#aplicarDecisao} sempre vem de um {@link SolicitacaoCriada}
 * ou de um {@code RegistroIdempotencia.solicitacaoId()} validos -- mas existe para que um erro cru
 * de "nenhuma linha encontrada" do JDBC nunca vaze da porta de persistencia sem contexto de
 * dominio.
 *
 * <p>Deliberadamente NAO implementa {@link ErroDeAplicacaoComCodigo}: e violacao de invariante
 * interna do sistema, nao um erro de negocio com um codigo estavel mapeavel na borda web.
 */
public class SolicitacaoNaoEncontradaException extends RuntimeException {

    public SolicitacaoNaoEncontradaException(String message) {
        super(message);
    }
}
