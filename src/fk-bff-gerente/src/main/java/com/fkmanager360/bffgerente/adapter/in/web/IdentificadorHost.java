package com.fkmanager360.bffgerente.adapter.in.web;

import java.util.regex.Pattern;

/**
 * Validacao de formato dos identificadores que viajam no caminho das rotas do BFF
 * ({@code clienteId}, {@code contaId}). Nasceu duplicada em {@code AtendimentoController} e
 * {@code SolicitacaoAumentoLimiteProxyController} -- mesma regex, mesma mensagem, mesmo tipo de
 * excecao -- e foi extraida aqui (achado C1 do code review de #0003).
 *
 * <p><b>Escopo deliberadamente minimo.</b> E utilitario tecnico local deste deployable: package-private,
 * sem interface, sem bean, sem framework de validacao. A duplicacao que ADR-0011 defende e a que
 * atravessa bounded contexts (as copias de {@code HostFormat}, {@code CoreLegadoCall} e
 * {@code AudienceValidator} continuam corretas e nao sao tocadas); duplicar dentro do MESMO modulo
 * nao tem essa justificativa -- sao dois pontos de manutencao para uma regra so.
 *
 * <p><b>Por que a validacao existe</b>: um identificador fora do formato host nunca deveria virar
 * uma chamada remota que so vai falhar la na frente. Sem ela, o {@code 400} que um Resource Server
 * devolveria escaparia da taxonomia de {@link GlobalExceptionHandler} e viraria {@code 500}
 * generico (achado I1 do review de #0002 -- a razao original de ela existir).
 *
 * <p>O {@link IllegalArgumentException} lancado aqui e o mesmo que {@code GlobalExceptionHandler}
 * ja traduz para {@code 400} com {@code codigo = IDENTIFICADOR_INVALIDO}: regex, mensagem, tipo de
 * excecao, status e codigo permanecem exatamente os de antes da extracao.
 */
final class IdentificadorHost {

    private static final Pattern FORMATO = Pattern.compile("[0-9]{1,10}");

    private IdentificadorHost() {
    }

    static void validar(String valor, String nomeDoCampo) {
        if (valor == null || !FORMATO.matcher(valor).matches()) {
            throw new IllegalArgumentException(nomeDoCampo + " deve ser numerico, com ate 10 digitos: " + valor);
        }
    }
}
