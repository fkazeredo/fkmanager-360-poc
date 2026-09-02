package com.fkmanager360.carteiraclientes.adapter.out.legacy;

import com.fkmanager360.carteiraclientes.application.port.out.CoreLegadoUnavailableException;
import com.fkmanager360.carteiraclientes.application.port.out.CoreLegadoTimeoutException;
import com.fkmanager360.carteiraclientes.application.port.out.DadosMestresClientePort;
import com.fkmanager360.carteiraclientes.application.port.out.InvalidCoreLegadoResponseException;
import com.fkmanager360.carteiraclientes.domain.ClienteId;
import com.fkmanager360.carteiraclientes.domain.DadosMestresCliente;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ACL propria de CarteiraClientes sobre o CoreLegado (ADR-0004): a unica classe deste servico que
 * conhece o vocabulario host-centric de {@code simulador-core-legado}. Nenhum {@code COD-RET}
 * atravessa esta fronteira (ADR-0005) -- sai como {@link DadosMestresCliente} valido, como
 * ausencia (nao encontrado) ou como uma das excecoes tipadas desta porta.
 */
@Component
public class ClienteLegadoAclAdapter implements DadosMestresClientePort {

    private static final String COD_RET_SUCESSO = "000";
    private static final String COD_RET_NAO_ENCONTRADO = "104";

    private final RestClient restClient;

    public ClienteLegadoAclAdapter(@Qualifier("clienteLegadoRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public Map<ClienteId, DadosMestresCliente> buscarDadosMestres(List<ClienteId> clienteIds) {
        if (clienteIds.isEmpty()) {
            return Map.of();
        }

        AclBatchQueryRequest request = new AclBatchQueryRequest(clienteIds.stream().map(this::toCodCli).toList());

        AclBatchQueryResponse response;
        try {
            response = restClient.post()
                    .uri("/legado/clientes/consulta-lote")
                    .body(request)
                    .retrieve()
                    .body(AclBatchQueryResponse.class);
        } catch (HttpServerErrorException e) {
            throw new CoreLegadoUnavailableException("CoreLegado respondeu erro de servidor: " + e.getStatusCode(), e);
        } catch (HttpClientErrorException e) {
            throw new InvalidCoreLegadoResponseException("CoreLegado recusou a requisicao: " + e.getStatusCode(), e);
        } catch (RestClientException e) {
            // A causa raiz e o sinal confiavel aqui: dependendo da versao do Spring, uma falha de
            // I/O (timeout, reset, conexao recusada) pode chegar embrulhada em tipos diferentes de
            // RestClientException, nao so ResourceAccessException. Inspecionar a cadeia de causas
            // e mais robusto do que apostar num unico tipo de wrapper.
            if (causeContains(e, SocketTimeoutException.class)) {
                throw new CoreLegadoTimeoutException("Tempo esgotado ao consultar o CoreLegado", e);
            }
            if (causeContains(e, IOException.class)) {
                throw new CoreLegadoUnavailableException("Falha de transporte ao consultar o CoreLegado", e);
            }
            throw new InvalidCoreLegadoResponseException("Resposta do CoreLegado nao pode ser interpretada", e);
        }

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

        ClienteId clienteId = new ClienteId(stripLeadingZeros(item.codCli()));

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

    private String toCodCli(ClienteId clienteId) {
        return "%010d".formatted(Long.parseLong(clienteId.valor()));
    }

    private static String stripLeadingZeros(String codCli) {
        String semZeros = codCli.replaceFirst("^0+(?=\\d)", "");
        return semZeros.isEmpty() ? "0" : semZeros;
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

    private static boolean causeContains(Throwable exception, Class<? extends Throwable> type) {
        Throwable cause = exception.getCause();
        while (cause != null) {
            if (type.isInstance(cause)) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
