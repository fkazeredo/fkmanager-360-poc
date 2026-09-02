package com.fkmanager360.carteiraclientes.adapters.saida.acl;

import com.fkmanager360.carteiraclientes.aplicacao.portas.CoreLegadoIndisponivelException;
import com.fkmanager360.carteiraclientes.aplicacao.portas.CoreLegadoTimeoutException;
import com.fkmanager360.carteiraclientes.aplicacao.portas.PortaDadosMestresCliente;
import com.fkmanager360.carteiraclientes.aplicacao.portas.RespostaCoreLegadoInvalidaException;
import com.fkmanager360.carteiraclientes.dominio.ClienteId;
import com.fkmanager360.carteiraclientes.dominio.DadosMestresCliente;
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
public class ClienteLegadoAclAdapter implements PortaDadosMestresCliente {

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

        ConsultaLoteRequisicaoAcl requisicao = new ConsultaLoteRequisicaoAcl(clienteIds.stream().map(this::paraCodCli).toList());

        ConsultaLoteRespostaAcl resposta;
        try {
            resposta = restClient.post()
                    .uri("/legado/clientes/consulta-lote")
                    .body(requisicao)
                    .retrieve()
                    .body(ConsultaLoteRespostaAcl.class);
        } catch (HttpServerErrorException e) {
            throw new CoreLegadoIndisponivelException("CoreLegado respondeu erro de servidor: " + e.getStatusCode(), e);
        } catch (HttpClientErrorException e) {
            throw new RespostaCoreLegadoInvalidaException("CoreLegado recusou a requisicao: " + e.getStatusCode(), e);
        } catch (RestClientException e) {
            // A causa raiz e o sinal confiavel aqui: dependendo da versao do Spring, uma falha de
            // I/O (timeout, reset, conexao recusada) pode chegar embrulhada em tipos diferentes de
            // RestClientException, nao so ResourceAccessException. Inspecionar a cadeia de causas
            // e mais robusto do que apostar num unico tipo de wrapper.
            if (causaContem(e, SocketTimeoutException.class)) {
                throw new CoreLegadoTimeoutException("Tempo esgotado ao consultar o CoreLegado", e);
            }
            if (causaContem(e, IOException.class)) {
                throw new CoreLegadoIndisponivelException("Falha de transporte ao consultar o CoreLegado", e);
            }
            throw new RespostaCoreLegadoInvalidaException("Resposta do CoreLegado nao pode ser interpretada", e);
        }

        if (resposta == null || resposta.clientes() == null) {
            throw new RespostaCoreLegadoInvalidaException("CoreLegado devolveu lote vazio ou malformado");
        }

        Map<ClienteId, DadosMestresCliente> resultado = new HashMap<>();
        for (ConsultaLoteRespostaAcl.ItemRespostaAcl item : resposta.clientes()) {
            aplicarItem(resultado, item);
        }
        return resultado;
    }

    private void aplicarItem(Map<ClienteId, DadosMestresCliente> resultado, ConsultaLoteRespostaAcl.ItemRespostaAcl item) {
        if (item == null || item.codCli() == null || item.codRet() == null) {
            throw new RespostaCoreLegadoInvalidaException("Item do lote sem codCli ou codRet");
        }

        ClienteId clienteId = new ClienteId(semZeroPadding(item.codCli()));

        switch (item.codRet()) {
            case COD_RET_SUCESSO -> resultado.put(clienteId, new DadosMestresCliente(
                    nomeOuVazio(item.nomCli()), cpfMascarado(item.numCpf())));
            case COD_RET_NAO_ENCONTRADO -> {
                // Ausencia deliberada do mapa: o chamador decide o que fazer com "nao encontrado".
            }
            default -> throw new RespostaCoreLegadoInvalidaException(
                    "COD-RET desconhecido do CoreLegado para " + clienteId.valor() + ": " + item.codRet());
        }
    }

    private String paraCodCli(ClienteId clienteId) {
        return "%010d".formatted(Long.parseLong(clienteId.valor()));
    }

    private static String semZeroPadding(String codCli) {
        String semZeros = codCli.replaceFirst("^0+(?=\\d)", "");
        return semZeros.isEmpty() ? "0" : semZeros;
    }

    private static String nomeOuVazio(String nomCli) {
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

    private static boolean causaContem(Throwable excecao, Class<? extends Throwable> tipo) {
        Throwable causa = excecao.getCause();
        while (causa != null) {
            if (tipo.isInstance(causa)) {
                return true;
            }
            causa = causa.getCause();
        }
        return false;
    }
}
