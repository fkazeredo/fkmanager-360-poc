package com.fkmanager360.carteiraclientes.adapter.in.web;

import com.fkmanager360.carteiraclientes.domain.ContextoAtendimento;
import io.swagger.v3.oas.annotations.media.Schema;

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
        @Schema(description = "O clienteId autoritativo -- o unico campo que fk-servico-credito consome.",
                example = "1", requiredMode = Schema.RequiredMode.REQUIRED) String clienteId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String nome,
        @Schema(example = "***.456.789-**", requiredMode = Schema.RequiredMode.REQUIRED) String cpfMascarado,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ContaResumoResponse conta) {

    static ContextoAtendimentoResponse de(ContextoAtendimento contexto) {
        return new ContextoAtendimentoResponse(
                contexto.clienteId().valor(),
                contexto.dadosMestres().nome(),
                contexto.dadosMestres().cpfMascarado(),
                ContaResumoResponse.de(contexto.conta()));
    }
}
