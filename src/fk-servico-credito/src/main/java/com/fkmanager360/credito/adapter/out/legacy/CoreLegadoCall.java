package com.fkmanager360.credito.adapter.out.legacy;

import com.fkmanager360.credito.application.port.out.CoreLegadoTimeoutException;
import com.fkmanager360.credito.application.port.out.CoreLegadoUnavailableException;
import com.fkmanager360.credito.application.port.out.InvalidCoreLegadoResponseException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.function.Supplier;

/**
 * A distincao que ADR-0005 exige da ACL: falha de <b>transporte</b> nao e a mesma coisa que
 * resposta tecnica valida carregando retorno de erro do host. Esta classe cobre so a primeira
 * metade; a segunda e o {@code COD-RET}, interpretado pelo adapter segundo a semantica da sua
 * propria operacao.
 */
final class CoreLegadoCall {

    private CoreLegadoCall() {
    }

    static <T> T execute(Supplier<T> chamada, String descricaoDaOperacao) {
        try {
            return chamada.get();
        } catch (HttpServerErrorException e) {
            throw new CoreLegadoUnavailableException(
                    "CoreLegado respondeu erro de servidor ao " + descricaoDaOperacao + ": " + e.getStatusCode(), e);
        } catch (HttpClientErrorException e) {
            throw new InvalidCoreLegadoResponseException(
                    "CoreLegado recusou a requisicao ao " + descricaoDaOperacao + ": " + e.getStatusCode(), e);
        } catch (RestClientException e) {
            // A causa raiz e o sinal confiavel: dependendo da versao do Spring, uma falha de I/O
            // pode chegar embrulhada em tipos diferentes de RestClientException, nao so
            // ResourceAccessException.
            if (causeContains(e, SocketTimeoutException.class)) {
                throw new CoreLegadoTimeoutException("Tempo esgotado ao " + descricaoDaOperacao, e);
            }
            if (causeContains(e, IOException.class)) {
                throw new CoreLegadoUnavailableException("Falha de transporte ao " + descricaoDaOperacao, e);
            }
            throw new InvalidCoreLegadoResponseException(
                    "Resposta do CoreLegado nao pode ser interpretada ao " + descricaoDaOperacao, e);
        }
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
