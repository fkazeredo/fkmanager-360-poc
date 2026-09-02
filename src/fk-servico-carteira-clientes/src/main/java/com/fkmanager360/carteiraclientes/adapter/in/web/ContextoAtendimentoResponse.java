package com.fkmanager360.carteiraclientes.adapter.in.web;

import com.fkmanager360.carteiraclientes.domain.ContextoAtendimento;

/**
 * O contexto de atendimento na borda, com dois consumidores de necessidades diferentes: o
 * bff-gerente usa tudo, para compor a tela; servico-credito usa apenas {@code clienteId} e o
 * proprio fato de ter recebido 200 em vez de 403.
 *
 * <p>Ha overfetch reconhecido do lado de Credito, que recebe na rede dados cadastrais que nao
 * desserializa nem usa. Fica assim deliberadamente: separar uma operacao de autorizacao pura
 * exigiria evidencia que este ticket ainda nao tem, e o consumidor que justifica a forma rica --
 * a composicao de tela -- e real hoje.
 */
record ContextoAtendimentoResponse(
        String clienteId,
        String nome,
        String cpfMascarado,
        ContaResumoResponse conta) {

    static ContextoAtendimentoResponse de(ContextoAtendimento contexto) {
        return new ContextoAtendimentoResponse(
                contexto.clienteId().valor(),
                contexto.dadosMestres().nome(),
                contexto.dadosMestres().cpfMascarado(),
                ContaResumoResponse.de(contexto.conta()));
    }
}
