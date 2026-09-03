package com.fkmanager360.carteiraclientes.adapter.out.legacy;

import com.fkmanager360.carteiraclientes.application.port.out.DadosMestresClientePort;
import com.fkmanager360.carteiraclientes.application.port.out.InvalidCoreLegadoResponseException;
import com.fkmanager360.carteiraclientes.domain.ClienteId;
import com.fkmanager360.carteiraclientes.domain.DadosMestresCliente;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A fatia da ACL deste contexto que traduz os dados mestres do Cliente (ADR-0004). Nenhum
 * {@code COD-RET} atravessa esta fronteira (ADR-0005) -- sai como {@link DadosMestresCliente}
 * valido, como ausencia (nao encontrado) ou como uma das excecoes tipadas desta porta.
 */
@Component
@RequiredArgsConstructor
public class ClienteLegadoAclAdapter implements DadosMestresClientePort {

    private static final String COD_RET_SUCESSO = "000";
    private static final String COD_RET_NAO_ENCONTRADO = "104";

    @Qualifier("coreLegadoRestClient")
    private final RestClient restClient;

    @Override
    public Map<ClienteId, DadosMestresCliente> buscarDadosMestres(List<ClienteId> clienteIds) {
        if (clienteIds.isEmpty()) {
            return Map.of();
        }

        AclBatchQueryRequest request = new AclBatchQueryRequest(
                clienteIds.stream().map(id -> HostFormat.toCodigoHost(id.valor())).toList());

        AclBatchQueryResponse response = CoreLegadoCall.execute(
                () -> restClient.post()
                        .uri("/legado/clientes/consulta-lote")
                        .body(request)
                        .retrieve()
                        .body(AclBatchQueryResponse.class),
                "consultar os dados mestres do Cliente no CoreLegado");

        if (response == null || response.clientes() == null) {
            throw new InvalidCoreLegadoResponseException("CoreLegado devolveu lote vazio ou malformado");
        }

        Map<ClienteId, DadosMestresCliente> result = new HashMap<>();
        for (AclBatchQueryResponse.AclResponseItem item : response.clientes()) {
            applyItem(result, item);
        }
        return result;
    }

    private void applyItem(Map<ClienteId, DadosMestresCliente> result, AclBatchQueryResponse.AclResponseItem item) {
        if (item == null || item.codCli() == null || item.codRet() == null) {
            throw new InvalidCoreLegadoResponseException("Item do lote sem codCli ou codRet");
        }

        ClienteId clienteId = new ClienteId(HostFormat.stripLeadingZeros(item.codCli()));

        switch (item.codRet()) {
            case COD_RET_SUCESSO -> result.put(clienteId, new DadosMestresCliente(
                    nameOrBlank(item.nomCli()), cpfMascarado(item.numCpf())));
            case COD_RET_NAO_ENCONTRADO -> {
                // Ausencia deliberada do mapa: o chamador decide o que fazer com "nao encontrado".
            }
            default -> throw new InvalidCoreLegadoResponseException(
                    "COD-RET desconhecido do CoreLegado para " + clienteId.valor() + ": " + item.codRet());
        }
    }

    private static String nameOrBlank(String nomCli) {
        // Campos opcionais em branco sao representacao host valida, nao erro (ADR-0005).
        return nomCli == null ? "" : nomCli.trim();
    }

    /**
     * Mascara o CPF preservando os dois blocos centrais, ao padrao ***.XXX.YYY-**. Tolerante a
     * zero-padding inesperado ou formato invalido do host: fora do formato de 11 digitos, o
     * campo e tratado como nao informado em vez de propagar um valor malformado.
     */
    private static String cpfMascarado(String numCpf) {
        if (numCpf == null || !numCpf.matches("\\d{11}")) {
            return "";
        }
        String bloco2 = numCpf.substring(3, 6);
        String bloco3 = numCpf.substring(6, 9);
        return "***." + bloco2 + "." + bloco3 + "-**";
    }
}
