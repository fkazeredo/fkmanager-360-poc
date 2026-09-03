package com.fkmanager360.simuladorcorelegado.adapter.in.web;

import com.fkmanager360.simuladorcorelegado.domain.ContasLegadoStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * As duas capacidades de conta que este ticket exige, deliberadamente separadas por consumidor
 * (ADR-0004): a consulta de contas de um Cliente e usada pela ACL de CarteiraClientes e nao
 * devolve nenhum dado financeiro; a consulta de credito e usada pela ACL de Credito e devolve
 * apenas os fatos de credito, sem nada cadastral.
 *
 * <p>Sem autenticacao, pela mesma decisao consciente registrada em {@link ClienteLegadoController}.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "conta-legado", description = "Consulta de contas de um Cliente e dos dados de credito de uma conta.")
public class ContaLegadoController {

    private final ContasLegadoStore store;

    @Operation(
            operationId = "consultarContasDoClienteLegado",
            summary = "Contas correntes de um Cliente, pelo codigo host",
            description = "A chave e sempre codCli. Nao existe consulta de conta por numCta neste "
                    + "contrato, e a ausencia e deliberada: nenhum consumidor precisa descobrir o dono de "
                    + "uma conta antes de ja estar autorizado sobre o Cliente, e oferecer essa chave "
                    + "conveniente conviteria a inversao de ordem que a autorizacao de recurso proibe "
                    + "(ADR-0007). Devolve apenas identificacao -- nenhum dado financeiro, porque quem "
                    + "consome esta operacao e CarteiraClientes, que nao e fachada financeira (ADR-0004).")
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Requisicao processada. \"Nenhuma ocorrencia\" chega como codRet 121 "
                            + "dentro do 200, e nao como 404 -- o status HTTP nao carrega o resultado de "
                            + "negocio (ADR-0005).",
                    content = @Content(schema = @Schema(implementation = ContasLegadoQueryResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "codCli ausente ou fora do formato de 10 digitos.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
    })
    @PostMapping(path = "/legado/contas/consulta")
    public ContasLegadoQueryResponse consultarContasDoCliente(@Valid @RequestBody ContasLegadoQueryRequest requisicao) {
        List<ContaLegadoItemResponse> contas = store.findByCodCli(requisicao.codCli()).stream()
                .map(ContaLegadoItemResponse::de)
                .toList();

        return contas.isEmpty()
                ? ContasLegadoQueryResponse.nenhumaOcorrencia(requisicao.codCli())
                : ContasLegadoQueryResponse.encontradas(requisicao.codCli(), contas);
    }

    @Operation(
            operationId = "consultarCreditoDaContaLegado",
            summary = "Dados de credito da conta -- limite, situacao e classificacao de risco base",
            description = "Os tres fatos de credito vem da MESMA resposta, para que a procedencia dentro "
                    + "de Credito seja registrada uma vez para o conjunto. Nenhum dado cadastral do Cliente "
                    + "aparece aqui: isso pertence a CarteiraClientes.")
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Requisicao processada; conta desconhecida chega como codRet 121.",
                    content = @Content(schema = @Schema(implementation = CreditoContaLegadoQueryResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "numCta ausente ou fora do formato de 10 digitos.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
    })
    @PostMapping(path = "/legado/contas/consulta-credito")
    public CreditoContaLegadoQueryResponse consultarCreditoDaConta(
            @Valid @RequestBody CreditoContaLegadoQueryRequest requisicao) {

        return store.findByNumCta(requisicao.numCta())
                .map(CreditoContaLegadoQueryResponse::de)
                .orElseGet(() -> CreditoContaLegadoQueryResponse.naoEncontrada(requisicao.numCta()));
    }
}
