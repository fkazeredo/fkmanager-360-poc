package com.fkmanager360.credito.adapter.in.web;

import com.fkmanager360.credito.application.ComandoSolicitacaoAumentoLimite;
import com.fkmanager360.credito.application.ResultadoSubmissao;
import com.fkmanager360.credito.application.usecase.RegistrarSolicitacaoAumentoLimite;
import com.fkmanager360.credito.domain.AtorId;
import com.fkmanager360.credito.domain.ClienteId;
import com.fkmanager360.credito.domain.ContaId;
import com.fkmanager360.credito.domain.IdempotencyKey;
import org.springframework.http.HttpStatus;
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
public class SolicitacaoAumentoLimiteController {

    private final RegistrarSolicitacaoAumentoLimite registrarSolicitacaoAumentoLimite;
    private final MetricasDecisaoCredito metricasDecisaoCredito;
    private final Clock clock;

    public SolicitacaoAumentoLimiteController(
            RegistrarSolicitacaoAumentoLimite registrarSolicitacaoAumentoLimite,
            MetricasDecisaoCredito metricasDecisaoCredito,
            Clock clock) {
        this.registrarSolicitacaoAumentoLimite = registrarSolicitacaoAumentoLimite;
        this.metricasDecisaoCredito = metricasDecisaoCredito;
        this.clock = clock;
    }

    @PostMapping("/clientes/{clienteId}/contas/{contaId}/solicitacoes-aumento-limite")
    ResponseEntity<SolicitacaoAumentoLimiteResponse> submeter(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String clienteId,
            @PathVariable String contaId,
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
