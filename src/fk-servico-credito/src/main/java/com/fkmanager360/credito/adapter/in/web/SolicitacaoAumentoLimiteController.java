package com.fkmanager360.credito.adapter.in.web;

import com.fkmanager360.credito.application.ComandoSolicitacaoAumentoLimite;
import com.fkmanager360.credito.application.ResultadoSubmissao;
import com.fkmanager360.credito.application.usecase.RegistrarSolicitacaoAumentoLimite;
import com.fkmanager360.credito.domain.AtorId;
import com.fkmanager360.credito.domain.ClienteId;
import com.fkmanager360.credito.domain.ContaId;
import com.fkmanager360.credito.domain.IdempotencyKey;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * A fronteira comportamental submissao -&gt; decisao automatica (spec, User Story 33; plano #0003,
 * secao 9). Orquestra a unica parte da Fase 0 que e responsabilidade da borda web -- header
 * {@code Idempotency-Key} presente e bem-formado -- e delega todo o resto (validacao semantica,
 * autorizacao de recurso, consulta ao CoreLegado, persistencia e decisao) a
 * {@link RegistrarSolicitacaoAumentoLimite}, ja documentado com a ordem exata.
 *
 * <p><b>GUARDRAIL DE AUTORIA -- estrutural, nao convencao.</b> {@code originadorId} e derivado
 * EXCLUSIVAMENTE de {@code jwt.getSubject()}, nunca do corpo da requisicao --
 * {@link SolicitacaoAumentoLimiteRequest} nem declara esse campo. E o mesmo padrao ja usado em
 * {@code CarteiraClientes.AtendimentoController} e em {@link LimiteController} deste modulo. Este
 * e o {@link AtorId} que define autoria ({@code AtorHumano}) e o namespace
 * {@code (originadorId, Idempotency-Key)} de toda a idempotencia: um bug aqui permitiria um
 * gerente forjar autoria de outro, ou colidir/manipular o namespace de idempotencia de outro
 * gerente (SubmissaoSegurancaTest cobre isto explicitamente).
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "solicitacoes", description = "Submissao da SolicitacaoAumentoLimite e decisao automatica.")
public class SolicitacaoAumentoLimiteController {

    private final RegistrarSolicitacaoAumentoLimite registrarSolicitacaoAumentoLimite;
    private final MetricasDecisaoCredito metricasDecisaoCredito;
    private final Clock clock;

    @Operation(
            operationId = "submeterSolicitacaoAumentoLimite",
            summary = "Registra a ManifestacaoCliente e o LimiteSolicitado, devolve a decisao automatica",
            description = "clienteId/contaId vem do path -- autoritativos porque a autorizacao em "
                    + "CarteiraClientes ja aconteceu por eles. O corpo NAO aceita clienteId, contaId "
                    + "nem origemSolicitacao. originadorId vem exclusivamente do subject do JWT. "
                    + "201 para toda criacao (inclusive REJEITADA); 200 para replay idempotente.")
    @SecurityRequirement(name = "bearerJwt", scopes = "credito.escrita")
    @ApiResponses({
            @ApiResponse(responseCode = "201",
                    description = "SolicitacaoAumentoLimite criada agora (independente do resultado da decisao).",
                    content = @Content(schema = @Schema(implementation = SolicitacaoAumentoLimiteResponse.class))),
            @ApiResponse(responseCode = "200",
                    description = "Replay idempotente -- mesma chave, mesmo fingerprint, operacao ja concluida.",
                    content = @Content(schema = @Schema(implementation = SolicitacaoAumentoLimiteResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "IDEMPOTENCY_KEY_AUSENTE, IDEMPOTENCY_KEY_INVALIDA ou COMANDO_ILEGIVEL.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Sem token, expirado, assinatura/issuer/audience invalidos.",
                    content = @Content),
            @ApiResponse(responseCode = "403",
                    description = "Sem direito de atendimento atual. codigo = SEM_DIREITO_DE_ATENDIMENTO.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404",
                    description = "Conta nao reconhecida pelo CoreLegado. codigo = CONTA_NAO_ENCONTRADA.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409",
                    description = "LIMITE_VIGENTE_DESATUALIZADO, SOLICITACAO_NAO_TERMINAL_EXISTENTE ou IDEMPOTENCIA_EM_PROCESSAMENTO.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "422",
                    description = "COMANDO_INVALIDO, LIMITE_SOLICITADO_NAO_AUMENTA ou IDEMPOTENCIA_FINGERPRINT_DIVERGENTE.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "502",
                    description = "CoreLegado respondeu algo que a ACL nao sabe interpretar. codigo = DEPENDENCIA_INDISPONIVEL.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503",
                    description = "CoreLegado ou fk-servico-carteira-clientes indisponivel. codigo = DEPENDENCIA_INDISPONIVEL.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "504",
                    description = "Timeout ao consultar o CoreLegado. codigo = DEPENDENCIA_INDISPONIVEL.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
    })
    @PostMapping("/clientes/{clienteId}/contas/{contaId}/solicitacoes-aumento-limite")
    ResponseEntity<SolicitacaoAumentoLimiteResponse> submeter(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @Parameter(example = "1") @PathVariable String clienteId,
            @Parameter(example = "10001") @PathVariable String contaId,
            @Parameter(description = "Identifica uma tentativa logica de submissao com payload canonico "
                    + "fixo. Escopo de unicidade: originadorId + key.", required = true)
            @RequestHeader("Idempotency-Key") String idempotencyKeyHeader,
            @RequestBody SolicitacaoAumentoLimiteRequest request) {

        IdempotencyKey idempotencyKey = parsearIdempotencyKey(idempotencyKeyHeader);

        var manifestacao = request.manifestacaoCliente();
        ComandoSolicitacaoAumentoLimite comando = new ComandoSolicitacaoAumentoLimite(
                new ClienteId(clienteId),
                new ContaId(contaId),
                request.limiteSolicitado(),
                request.limiteVigenteVisto(),
                manifestacao == null ? null : manifestacao.canalManifestacao(),
                manifestacao == null ? null : manifestacao.observacao(),
                new AtorId(jwt.getSubject()),
                idempotencyKey);

        Instant agora = clock.instant();
        ResultadoSubmissao resultado = registrarSolicitacaoAumentoLimite.executar(comando, agora);

        metricasDecisaoCredito.registrarDecisao(resultado);

        HttpStatus status = resultado.criacaoNova() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(SolicitacaoAumentoLimiteResponse.de(resultado, agora));
    }

    private static IdempotencyKey parsearIdempotencyKey(String idempotencyKeyHeader) {
        try {
            return new IdempotencyKey(UUID.fromString(idempotencyKeyHeader));
        } catch (IllegalArgumentException e) {
            throw new IdempotencyKeyInvalidaException(
                    "Idempotency-Key nao e um UUID valido: " + idempotencyKeyHeader);
        }
    }
}
