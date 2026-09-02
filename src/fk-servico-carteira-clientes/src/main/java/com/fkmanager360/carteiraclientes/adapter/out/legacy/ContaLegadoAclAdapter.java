package com.fkmanager360.carteiraclientes.adapter.out.legacy;

import com.fkmanager360.carteiraclientes.application.port.out.ContasClientePort;
import com.fkmanager360.carteiraclientes.application.port.out.InvalidCoreLegadoResponseException;
import com.fkmanager360.carteiraclientes.domain.ClienteId;
import com.fkmanager360.carteiraclientes.domain.ContaCorrente;
import com.fkmanager360.carteiraclientes.domain.ContaId;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * A fatia da ACL deste contexto que traduz a identificacao de ContaCorrente (ADR-0004). Nenhum
 * {@code COD-RET} atravessa esta fronteira (ADR-0005): sai como lista de {@link ContaCorrente},
 * como lista vazia (nenhuma conta) ou como uma das excecoes tipadas da porta.
 *
 * <p>Nao le, nao pede e nao sabe interpretar limite, saldo ou situacao da conta -- esses fatos
 * pertencem a Credito e sao lidos pela ACL daquele contexto.
 */
@Component
public class ContaLegadoAclAdapter implements ContasClientePort {

    private static final String COD_RET_SUCESSO = "000";
    private static final String COD_RET_CONTA_NAO_ENCONTRADA = "121";

    private final RestClient restClient;

    public ContaLegadoAclAdapter(@Qualifier("coreLegadoRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public List<ContaCorrente> buscarContasDoCliente(ClienteId clienteId) {
        AclContasQueryResponse response = CoreLegadoCall.execute(
                () -> restClient.post()
                        .uri("/legado/contas/consulta")
                        .body(new AclContasQueryRequest(HostFormat.toCodigoHost(clienteId.valor())))
                        .retrieve()
                        .body(AclContasQueryResponse.class),
                "consultar as contas do Cliente no CoreLegado");

        if (response == null || response.codRet() == null) {
            throw new InvalidCoreLegadoResponseException("CoreLegado devolveu resposta de contas sem COD-RET");
        }

        return switch (response.codRet()) {
            case COD_RET_SUCESSO -> traduzir(response.contas());
            // Nenhuma ocorrencia e resposta legitima do host, nao erro: o Cliente simplesmente nao
            // tem conta. Quem decide o que fazer com a ausencia e o chamador.
            case COD_RET_CONTA_NAO_ENCONTRADA -> List.of();
            default -> throw new InvalidCoreLegadoResponseException(
                    "COD-RET desconhecido do CoreLegado na consulta de contas: " + response.codRet());
        };
    }

    private static List<ContaCorrente> traduzir(List<AclContasQueryResponse.AclContaItem> contas) {
        if (contas == null) {
            throw new InvalidCoreLegadoResponseException("CoreLegado devolveu COD-RET de sucesso sem lista de contas");
        }
        return contas.stream().map(ContaLegadoAclAdapter::traduzirItem).toList();
    }

    private static ContaCorrente traduzirItem(AclContasQueryResponse.AclContaItem item) {
        if (item == null || item.numCta() == null || item.numCta().isBlank()) {
            throw new InvalidCoreLegadoResponseException("Item de conta sem numero de conta");
        }
        try {
            // ContaId canonicaliza no proprio construtor -- nao precisa mais de
            // HostFormat.stripLeadingZeros aqui.
            return new ContaCorrente(
                    new ContaId(item.numCta()),
                    // Agencia e identificacao, exibida como o host a representa; em branco e
                    // campo opcional ausente (ADR-0005), nao erro.
                    item.codAge() == null ? "" : item.codAge().trim());
        } catch (IllegalArgumentException e) {
            throw new InvalidCoreLegadoResponseException(
                    "Numero de conta fora do formato esperado: " + item.numCta(), e);
        }
    }
}
