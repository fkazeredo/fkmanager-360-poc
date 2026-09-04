package com.fkmanager360.credito.adapter.in.web;

import com.fkmanager360.credito.application.port.out.ResultadoEfetivacaoRecebido;
import com.fkmanager360.credito.application.port.out.ResultadoRegistroEfetivacao;
import com.fkmanager360.credito.application.port.out.SolicitacaoNaoEncontradaException;
import com.fkmanager360.credito.application.usecase.RegistrarResultadoEfetivacao;
import com.fkmanager360.credito.domain.AtorSistema;
import com.fkmanager360.credito.domain.EfetivacaoId;
import com.fkmanager360.credito.domain.MotivoFalhaEfetivacao;
import com.fkmanager360.credito.domain.ProtocoloCore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * O CoreLegado confirma o resultado de uma efetivacao chamando de volta este endpoint (spec,
 * secao "Callback"; ticket #0005). Autenticado {@code client_credentials} (maquina-a-maquina --
 * ver {@code SecurityConfig}), correlaciona por {@code idEft} (nunca por {@code numPrt}, que pode
 * ser justamente o que se perdeu no aceite) e converge no MESMO caso de uso unico de conclusao
 * criado em #0004 ({@link RegistrarResultadoEfetivacao#executar}) -- nenhuma segunda
 * implementacao da regra de conclusao (ADR-0009).
 *
 * <p><b>Idempotente por construcao, preparado para redelivery at-least-once.</b> A garantia de
 * entrega deste endpoint e da responsabilidade do CHAMADOR (o CoreLegado, ou -- na ausencia de
 * uma resposta autoritativa dentro da janela normal -- o reconciliador de #0006): este endpoint
 * apenas processa corretamente qualquer numero de redeliveries do MESMO fato, nunca reenvia nada
 * por conta propria. Duplicado identico (AC13), antecipado com relacao ao aceite (AC14) e
 * contraditorio sobre estado terminal (AC17) sao tratados aqui traduzindo a sealed
 * {@link ResultadoRegistroEfetivacao} que o caso de uso devolve -- toda a REGRA vive no adapter de
 * persistencia (S3), este controller so faz roteamento HTTP.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "callback", description = "Confirmacao autoritativa do resultado de uma efetivacao pelo CoreLegado.")
public class CallbackEfetivacaoController {

    private static final String COD_RET_SUCESSO = "000";

    private static final Map<String, MotivoFalhaEfetivacao> MOTIVOS_FALHA_DEFINITIVA = Map.of(
            "121", MotivoFalhaEfetivacao.CONTA_INEXISTENTE,
            "118", MotivoFalhaEfetivacao.CONTA_BLOQUEADA_NA_EFETIVACAO,
            "205", MotivoFalhaEfetivacao.LIMITE_VIGENTE_DIVERGENTE,
            "199", MotivoFalhaEfetivacao.INSTRUCAO_INVALIDA);

    private final RegistrarResultadoEfetivacao registrarResultadoEfetivacao;
    private final MetricasCallbackEfetivacao metricas;
    private final Clock clock;

    @Operation(
            operationId = "confirmarEfetivacao",
            summary = "Callback autoritativo do CoreLegado com o resultado de uma efetivacao",
            description = "Correlaciona por idEft. Duplicado identico, antecipado com relacao ao aceite e "
                    + "contraditorio sobre estado terminal sao tratados sem reescrever nada indevidamente -- "
                    + "ver corpo da resposta para a distincao tecnica entre os quatro sub-casos de 200.")
    @SecurityRequirement(name = "bearerJwt", scopes = "credito.callback")
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Processado, duplicado identico, conflito registrado ou anomalia registrada -- "
                            + "ver corpo. Sempre 2xx mesmo em contradicao, para nao transformar inconsistencia "
                            + "semantica em tempestade de redelivery.",
                    content = @Content(schema = @Schema(implementation = CallbackEfetivacaoResponse.class))),
            @ApiResponse(responseCode = "400", description = "Payload malformado: idEft nao e UUID valido, ou "
                    + "vlrLimEft ausente/ilegivel quando codRet=\"000\".", content = @Content),
            @ApiResponse(responseCode = "401", description = "Sem token, expirado, assinatura/issuer/audience invalidos.",
                    content = @Content),
            @ApiResponse(responseCode = "403", description = "Sem escopo credito.callback.", content = @Content),
            @ApiResponse(responseCode = "404", description = "idEft desconhecido -- registrado como ocorrencia; "
                    + "endpoint autenticado maquina-a-maquina, sem enumeracao a proteger perante o proprio Core.",
                    content = @Content),
    })
    @PostMapping("/callbacks/efetivacoes")
    ResponseEntity<CallbackEfetivacaoResponse> receber(@RequestBody CallbackEfetivacaoRequest requisicao) {
        // Sem Bean Validation neste modulo (ver Javadoc de CallbackEfetivacaoRequest): idEft,
        // numPrt e codRet sao String -- Jackson nunca falha a desserializacao so por estarem
        // ausentes ou em branco, entao a obrigatoriedade e verificada aqui, manualmente.
        if (emBranco(requisicao.idEft()) || emBranco(requisicao.numPrt()) || emBranco(requisicao.codRet())) {
            return ResponseEntity.badRequest().build();
        }

        UUID efetivacaoIdBruto;
        try {
            efetivacaoIdBruto = UUID.fromString(requisicao.idEft());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }

        ResultadoEfetivacaoRecebido resultado;
        if (COD_RET_SUCESSO.equals(requisicao.codRet())) {
            Long limiteEfetivado = parseLimiteEfetivado(requisicao.vlrLimEft());
            if (limiteEfetivado == null) {
                return ResponseEntity.badRequest().build();
            }
            resultado = new ResultadoEfetivacaoRecebido.Sucesso(limiteEfetivado);
        } else {
            MotivoFalhaEfetivacao motivo = MOTIVOS_FALHA_DEFINITIVA.get(requisicao.codRet());
            if (motivo == null) {
                metricas.registrarResultado(CallbackEfetivacaoResponse.ANOMALIA_REGISTRADA);
                metricas.registrarAnomalia("CALLBACK_RESULTADO_DESCONHECIDO");
                return ResponseEntity.ok(CallbackEfetivacaoResponse.anomaliaRegistrada(
                        "codRet desconhecido: " + requisicao.codRet()));
            }
            resultado = new ResultadoEfetivacaoRecebido.FalhaDefinitiva(motivo);
        }

        EfetivacaoId efetivacaoId = new EfetivacaoId(efetivacaoIdBruto);
        ResultadoRegistroEfetivacao registro;
        try {
            registro = registrarResultadoEfetivacao.executar(
                    efetivacaoId, resultado, Optional.of(new ProtocoloCore(requisicao.numPrt())),
                    AtorSistema.CORE_LEGADO, clock.instant());
        } catch (SolicitacaoNaoEncontradaException e) {
            metricas.registrarAnomalia("CALLBACK_EFETIVACAO_DESCONHECIDA");
            return ResponseEntity.notFound().build();
        }

        return switch (registro) {
            case ResultadoRegistroEfetivacao.Concluida concluida -> {
                metricas.registrarResultado(CallbackEfetivacaoResponse.PROCESSADO);
                metricas.registrarConclusaoAgora(concluida.permanenciaEmAguardandoEfetivacao());
                yield ResponseEntity.ok(CallbackEfetivacaoResponse.processado());
            }
            case ResultadoRegistroEfetivacao.JaTerminalIdentica ignored -> {
                metricas.registrarResultado(CallbackEfetivacaoResponse.JA_CONCLUIDA);
                yield ResponseEntity.ok(CallbackEfetivacaoResponse.jaConcluida());
            }
            case ResultadoRegistroEfetivacao.JaTerminalContraditoria ignored -> {
                metricas.registrarResultado(CallbackEfetivacaoResponse.CONFLITO_REGISTRADO);
                metricas.registrarAnomalia("CALLBACK_CONTRADITORIO");
                yield ResponseEntity.ok(CallbackEfetivacaoResponse.conflitoRegistrado(
                        "Resultado recebido contradiz o estado terminal ja registrado para esta efetivacao"));
            }
            case ResultadoRegistroEfetivacao.SucessoIncoerente ignored -> {
                metricas.registrarResultado(CallbackEfetivacaoResponse.ANOMALIA_REGISTRADA);
                metricas.registrarAnomalia("CALLBACK_SUCESSO_INCOERENTE");
                yield ResponseEntity.ok(CallbackEfetivacaoResponse.anomaliaRegistrada(
                        "limiteEfetivado incompativel com o limiteSolicitado desta efetivacao"));
            }
            case ResultadoRegistroEfetivacao.ProtocoloDivergente ignored -> {
                metricas.registrarResultado(CallbackEfetivacaoResponse.ANOMALIA_REGISTRADA);
                metricas.registrarAnomalia("CALLBACK_PROTOCOLO_DIVERGENTE");
                yield ResponseEntity.ok(CallbackEfetivacaoResponse.anomaliaRegistrada(
                        "numPrt informado diverge do ja persistido para este idEft"));
            }
        };
    }

    private static boolean emBranco(String valor) {
        return valor == null || valor.isBlank();
    }

    private static Long parseLimiteEfetivado(String vlrLimEft) {
        if (vlrLimEft == null || vlrLimEft.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(vlrLimEft.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
