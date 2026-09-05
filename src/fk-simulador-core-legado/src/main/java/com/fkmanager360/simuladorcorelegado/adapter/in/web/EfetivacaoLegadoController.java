package com.fkmanager360.simuladorcorelegado.adapter.in.web;

import com.fkmanager360.simuladorcorelegado.adapter.in.scheduling.ProcessadorEfetivacaoLegado;
import com.fkmanager360.simuladorcorelegado.domain.ContaLegadoRecord;
import com.fkmanager360.simuladorcorelegado.domain.ContasLegadoStore;
import com.fkmanager360.simuladorcorelegado.domain.EfetivacoesLegadoStore;
import com.fkmanager360.simuladorcorelegado.domain.EfetivacoesLegadoStore.DecisaoDeTransporte;
import com.fkmanager360.simuladorcorelegado.domain.EfetivacoesLegadoStore.RegistroEfetivacao;
import com.fkmanager360.simuladorcorelegado.domain.EfetivacoesLegadoStore.ResultadoConsultaStatus;
import com.fkmanager360.simuladorcorelegado.domain.EfetivacoesLegadoStore.ResultadoRegistroAceite;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * A operacao funcional de efetivacao (spec, secao "Contrato do simulador-core-legado"; plano
 * #0004, secao 7; ticket #0005): recepcao da instrucao com deduplicacao por {@code idEft} e
 * devolucao de {@code numPrt}. A alteracao de fato em {@link ContasLegadoStore} e o disparo do
 * callback de confirmacao acontecem de forma ASSINCRONA, apos um atraso curto, via
 * {@link ProcessadorEfetivacaoLegado} -- nunca sincronamente dentro desta chamada, porque o
 * aceite nao e conclusao (spec).
 *
 * <p>Consulta de status por protocolo/idEft ({@link #consultarStatus}, #0006) nasce aqui, porque
 * este e o primeiro comportamento que a consome (o reconciliador de {@code servico-credito}).
 *
 * <p>Sem autenticacao, pela mesma decisao consciente registrada em {@code ClienteLegadoController}.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "conta-legado", description = "Consulta de contas de um Cliente e dos dados de credito de uma conta.")
public class EfetivacaoLegadoController {

    private final EfetivacoesLegadoStore store;
    private final ContasLegadoStore contasStore;
    private final ProcessadorEfetivacaoLegado processador;

    @Operation(
            operationId = "efetivarLimiteLegado",
            summary = "Recepcao da instrucao de efetivacao do LimiteChequeEspecial, com deduplicacao por idEft",
            description = "idEft e idCor carregam UUID por extenso -- identidade de negocio, nao formato "
                    + "host-centric numerico. A MESMA instrucao reenviada (mesmo idEft, mesmo payload) nunca "
                    + "aplica a alteracao duas vezes e devolve o MESMO numPrt; o mesmo idEft com payload "
                    + "diferente e sempre rejeitado explicitamente (codRet 207), nunca tratado como operacao "
                    + "nova. Esta operacao NAO aplica a alteracao sincronamente -- o novo limite so passa a "
                    + "valer para /legado/contas/consulta-credito, e o callback so e disparado, apos o "
                    + "processamento assincrono (#0005).")
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Requisicao processada -- aceite, uma das quatro classes de falha "
                            + "definitiva, ou indisponibilidade de negocio conhecida (codRet 998) chegam aqui "
                            + "dentro do 200 (ADR-0005). Este simulador especificamente nunca emite 998 (seus "
                            + "cenarios de control-plane modelam indisponibilidade como HTTP 5xx real) -- o "
                            + "codigo existe no contrato e na ACL porque a taxonomia da operacao o preve como "
                            + "resposta de negocio valida vinda do host, verificado contra WireMock (S4), nao "
                            + "contra este simulador (S5).",
                    content = @Content(schema = @Schema(implementation = EfetivacaoLegadoResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "Campo obrigatorio ausente ou fora do formato esperado.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503",
                    description = "Indisponibilidade transitoria simulada pelo control plane de cenarios "
                            + "(ADR-0018, perfis local/demo/test) -- sem corpo. No cenario perder-aceite, o "
                            + "aceite E registrado antes do 503: o reenvio do mesmo idEft recupera o mesmo "
                            + "numPrt (AC11).",
                    content = @Content),
    })
    @PostMapping(path = "/legado/efetivacoes")
    public ResponseEntity<EfetivacaoLegadoResponse> efetivar(@Valid @RequestBody EfetivacaoLegadoRequest requisicao) {
        // Dedup por idEft PRECEDE o consumo do cenario de control-plane, nao o contrario: um
        // reenvio de idEft ja aceito e sempre idempotente (AC11), mesmo que um cenario de
        // indisponibilidade tenha sido armado para a mesma conta depois do aceite original --
        // caso contrario o reenvio consumiria o cenario e devolveria 503 espurio, quebrando a
        // garantia "reenvio nunca falha" documentada no contrato.
        Optional<RegistroEfetivacao> existente = store.buscarAceite(requisicao.idEft());
        if (existente.isPresent()) {
            return ResponseEntity.ok(payloadCompativel(existente.get(), requisicao)
                    ? EfetivacaoLegadoResponse.aceite(requisicao, existente.get().numPrt())
                    : EfetivacaoLegadoResponse.payloadIncompativel(requisicao));
        }

        DecisaoDeTransporte decisao = store.consumirCenario(requisicao.numCta());

        if (decisao instanceof DecisaoDeTransporte.Responder503Registrando) {
            // O aceite E registrado (processamento assincrono agendado, #0005) -- so a RESPOSTA
            // se perde. E exatamente esse descompasso que produz o callback antecipado real.
            registrarAceiteEAgendarProcessamento(requisicao);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        if (decisao instanceof DecisaoDeTransporte.Responder503SemRegistrar) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }

        Optional<ContaLegadoRecord> conta = contasStore.findByNumCta(requisicao.numCta());
        if (conta.isEmpty()) {
            return ResponseEntity.ok(EfetivacaoLegadoResponse.contaNaoEncontrada(requisicao));
        }
        // Qualquer sitCta diferente de "01" (regular) e tratada como bloqueio para efeito de
        // efetivacao -- fail-safe na direcao certa, mesmo criterio ja usado pela ACL de Credito
        // para SituacaoConta (nao existe "pior caso seguro" alem de recusar).
        if (!"01".equals(conta.get().sitCta())) {
            return ResponseEntity.ok(EfetivacaoLegadoResponse.contaBloqueada(requisicao));
        }
        if (Long.parseLong(conta.get().vlrLimChqEsp()) != Long.parseLong(requisicao.vlrLimChqEspEsp())) {
            return ResponseEntity.ok(EfetivacaoLegadoResponse.limiteVigenteDivergente(requisicao));
        }
        if (Long.parseLong(requisicao.vlrLimNov()) <= Long.parseLong(requisicao.vlrLimChqEspEsp())) {
            return ResponseEntity.ok(EfetivacaoLegadoResponse.instrucaoInvalida(requisicao));
        }

        RegistroEfetivacao novo = registrarAceiteEAgendarProcessamento(requisicao);
        return ResponseEntity.ok(EfetivacaoLegadoResponse.aceite(requisicao, novo.numPrt()));
    }

    @Operation(
            operationId = "consultarStatusEfetivacaoLegado",
            summary = "Consulta de status por ProtocoloCore ou por EfetivacaoId (#0006)",
            description = "Recuperavel por qualquer um dos dois identificadores (ADR-0009, emenda): numPrt "
                    + "quando conhecido, idEft quando o aceite se perdeu. Exatamente um dos dois campos deve "
                    + "vir preenchido. Nunca reenvia nem muta nada -- so responde o desfecho ja conhecido.")
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Processada (codRet 000, com vlrLimEft), ainda em processamento (codRet 301) "
                            + "ou desconhecida (codRet 404) -- nunca uma falha definitiva neste simulador, "
                            + "porque toda instrucao aceita eventualmente processa com sucesso (nenhum caminho "
                            + "assincrono de falha existe hoje); o contrato preve os codigos definitivos para "
                            + "completude, verificados contra WireMock do lado do adapter (S4).",
                    content = @Content(schema = @Schema(implementation = ConsultaStatusEfetivacaoLegadoResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "Nem idEft nem numPrt informados, ou os dois informados ao mesmo tempo.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
    })
    @PostMapping(path = "/legado/efetivacoes/consulta")
    public ResponseEntity<ConsultaStatusEfetivacaoLegadoResponse> consultarStatus(
            @RequestBody ConsultaStatusEfetivacaoLegadoRequest requisicao) {
        boolean temIdEft = !emBranco(requisicao.idEft());
        boolean temNumPrt = !emBranco(requisicao.numPrt());
        if (temIdEft == temNumPrt) {
            // Os dois ausentes OU os dois presentes: nenhuma das duas e uma consulta valida --
            // exatamente um identificador por chamada.
            return ResponseEntity.badRequest().build();
        }

        ResultadoConsultaStatus resultado = temIdEft
                ? store.consultarPorIdEft(requisicao.idEft())
                : store.consultarPorNumPrt(requisicao.numPrt());

        return ResponseEntity.ok(switch (resultado) {
            case ResultadoConsultaStatus.Processada processada -> ConsultaStatusEfetivacaoLegadoResponse.processada(
                    processada.idEft(), processada.numPrt(), processada.vlrLimEft());
            case ResultadoConsultaStatus.EmProcessamento emProcessamento -> ConsultaStatusEfetivacaoLegadoResponse.emProcessamento(
                    emProcessamento.idEft(), emProcessamento.numPrt());
            case ResultadoConsultaStatus.Desconhecida ignored -> ConsultaStatusEfetivacaoLegadoResponse.desconhecida(
                    requisicao.idEft(), requisicao.numPrt());
        });
    }

    private static boolean emBranco(String valor) {
        return valor == null || valor.isBlank();
    }

    /**
     * {@code criadoAgora} (#0005, guardrail do Owner) e o que impede que uma dedup concorrente
     * (duas chamadas simultaneas para o MESMO {@code idEft}, ambas passando pela checagem de
     * {@code buscarAceite} antes de qualquer uma commitar) agende o processamento duas vezes --
     * so a execucao que efetivamente CRIOU o registro em {@link EfetivacoesLegadoStore} agenda.
     */
    private RegistroEfetivacao registrarAceiteEAgendarProcessamento(EfetivacaoLegadoRequest requisicao) {
        ResultadoRegistroAceite resultado = store.registrarAceite(
                requisicao.idEft(), requisicao.numCta(), requisicao.vlrLimChqEspEsp(), requisicao.vlrLimNov());
        if (resultado.criadoAgora()) {
            processador.agendarProcessamento(
                    requisicao.idEft(), requisicao.numCta(), resultado.registro().numPrt(),
                    requisicao.vlrLimNov(), requisicao.idCor());
        }
        return resultado.registro();
    }

    private static boolean payloadCompativel(RegistroEfetivacao registro, EfetivacaoLegadoRequest requisicao) {
        return registro.numCta().equals(requisicao.numCta())
                && registro.vlrLimChqEspEsp().equals(requisicao.vlrLimChqEspEsp())
                && registro.vlrLimNov().equals(requisicao.vlrLimNov());
    }
}
