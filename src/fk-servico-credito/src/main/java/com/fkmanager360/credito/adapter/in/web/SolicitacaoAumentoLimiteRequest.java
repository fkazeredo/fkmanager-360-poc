package com.fkmanager360.credito.adapter.in.web;

/**
 * O corpo aceito na submissao (spec, secao "Submissao da SolicitacaoAumentoLimite"; plano #0003,
 * secao 9). Campos numericos ({@code limiteSolicitado}, {@code limiteVigenteVisto}) sao
 * {@link Long} boxed, e campos semanticos ({@code canalManifestacao}, {@code observacao}) sao
 * {@link String}, deliberadamente: um valor estruturalmente impossivel num campo numerico (por
 * exemplo {@code 600000.5}, ou uma string onde se espera numero) precisa falhar na
 * DESSERIALIZACAO Jackson -- {@code 400 COMANDO_ILEGIVEL} -- e nunca na validacao semantica do
 * caso de uso ({@code 422 COMANDO_INVALIDO}). {@code spring.jackson.deserialization.accept-float-as-int:
 * false} (application.yml) e o que faz {@code 600000.5} falhar em vez de truncar silenciosamente
 * para {@code 600000}.
 *
 * <p><b>Nao declara</b> {@code clienteId}, {@code contaId} nem {@code origemSolicitacao}/
 * {@code origem}: os dois primeiros vem do path (autoritativo, ja verificado em CarteiraClientes
 * antes desta chamada), e a origem e sempre {@code CLIENTE}, fixada pelo dominio em
 * {@code RegistrarSolicitacaoAumentoLimite} (User Story 16). O Jackson padrao deste modulo NAO
 * habilita {@code FAIL_ON_UNKNOWN_PROPERTIES} (padrao do Spring Boot e {@code false}) -- e isso,
 * combinado com a ausencia destes campos no record, e o que garante que um payload tentando
 * enviar {@code "clienteId": "999999"} ou {@code "originadorId": "outro-gerente"} e simplesmente
 * ignorado, nunca lido (AC27 e o guardrail de autoria do controller).
 */
record SolicitacaoAumentoLimiteRequest(
        Long limiteSolicitado,
        Long limiteVigenteVisto,
        ManifestacaoClienteRequest manifestacaoCliente) {

    record ManifestacaoClienteRequest(String canalManifestacao, String observacao) {}
}
