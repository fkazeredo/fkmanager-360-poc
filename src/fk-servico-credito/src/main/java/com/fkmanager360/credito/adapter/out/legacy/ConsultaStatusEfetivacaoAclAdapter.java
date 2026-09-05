package com.fkmanager360.credito.adapter.out.legacy;

import com.fkmanager360.credito.application.port.out.ConsultaStatusEfetivacaoCorePort;
import com.fkmanager360.credito.application.port.out.ResultadoConsultaStatusCore;
import com.fkmanager360.credito.domain.EfetivacaoId;
import com.fkmanager360.credito.domain.MotivoFalhaEfetivacao;
import com.fkmanager360.credito.domain.ProtocoloCore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.net.SocketTimeoutException;

/**
 * ACL propria de Credito sobre a consulta de status do CoreLegado (#0006; ADR-0004): unico lugar
 * que conhece o {@code COD-RET} desta operacao. Nunca lanca excecao para o chamador --
 * {@link ConsultaStatusEfetivacaoCorePort} classifica toda patologia observavel nas classes de
 * {@link ResultadoConsultaStatusCore}.
 *
 * <p>Mesma "regra de ouro" de {@link EfetivacaoLegadoAclAdapter} (#0004): erro HTTP tecnico nunca
 * produz {@link ResultadoConsultaStatusCore.FalhaDefinitiva} -- somente um {@code COD-RET}
 * definitivo e conhecido produz essa classe. Qualquer coisa que esta ACL nao saiba interpretar cai
 * em {@link ResultadoConsultaStatusCore.Indeterminada}, nunca em {@code FalhaDefinitiva} nem em
 * {@code Desconhecida} -- {@code Desconhecida} e reservada exclusivamente ao {@code COD-RET} que o
 * proprio contrato define para "identificador nao reconhecido pelo Core".
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ConsultaStatusEfetivacaoAclAdapter implements ConsultaStatusEfetivacaoCorePort {

    private static final String COD_RET_SUCESSO = "000";
    private static final String COD_RET_EM_PROCESSAMENTO = "301";
    private static final String COD_RET_DESCONHECIDA = "404";
    private static final String COD_RET_CONTA_NAO_ENCONTRADA = "121";
    private static final String COD_RET_CONTA_BLOQUEADA = "118";
    private static final String COD_RET_LIMITE_VIGENTE_DIVERGENTE = "205";
    private static final String COD_RET_INSTRUCAO_INVALIDA = "199";

    @Qualifier("reconciliacaoLegadoRestClient")
    private final RestClient restClient;

    @Override
    public ResultadoConsultaStatusCore consultarPorProtocolo(ProtocoloCore protocolo) {
        return consultar(null, protocolo.valor());
    }

    @Override
    public ResultadoConsultaStatusCore consultarPorEfetivacaoId(EfetivacaoId efetivacaoId) {
        return consultar(efetivacaoId.valor().toString(), null);
    }

    private ResultadoConsultaStatusCore consultar(String idEft, String numPrt) {
        ConsultaStatusEfetivacaoLegadoResponse body;
        try {
            body = restClient.post()
                    .uri("/legado/efetivacoes/consulta")
                    .body(new ConsultaStatusEfetivacaoLegadoRequest(idEft, numPrt))
                    .retrieve()
                    .body(ConsultaStatusEfetivacaoLegadoResponse.class);
        } catch (HttpServerErrorException e) {
            return indeterminada("CoreLegado respondeu erro de servidor a consulta de status: " + e.getStatusCode());
        } catch (HttpClientErrorException e) {
            return indeterminada("CoreLegado recusou a consulta de status com status inesperado: " + e.getStatusCode());
        } catch (RestClientException e) {
            if (CoreLegadoCall.causeContains(e, SocketTimeoutException.class)) {
                return indeterminada("Timeout ao consultar status de efetivacao no CoreLegado");
            }
            if (CoreLegadoCall.causeContains(e, IOException.class)) {
                return indeterminada("Falha de transporte ao consultar status de efetivacao no CoreLegado");
            }
            return indeterminada("Resposta do CoreLegado a consulta de status nao pode ser interpretada");
        }

        if (body == null || body.codRet() == null || body.codRet().isBlank()) {
            return indeterminada("CoreLegado devolveu resposta de consulta de status sem COD-RET");
        }

        return switch (body.codRet().trim()) {
            case COD_RET_SUCESSO -> traduzirSucesso(body);
            case COD_RET_EM_PROCESSAMENTO -> new ResultadoConsultaStatusCore.EmProcessamento();
            case COD_RET_DESCONHECIDA -> new ResultadoConsultaStatusCore.Desconhecida();
            case COD_RET_CONTA_NAO_ENCONTRADA -> new ResultadoConsultaStatusCore.FalhaDefinitiva(MotivoFalhaEfetivacao.CONTA_INEXISTENTE);
            case COD_RET_CONTA_BLOQUEADA -> new ResultadoConsultaStatusCore.FalhaDefinitiva(MotivoFalhaEfetivacao.CONTA_BLOQUEADA_NA_EFETIVACAO);
            case COD_RET_LIMITE_VIGENTE_DIVERGENTE -> new ResultadoConsultaStatusCore.FalhaDefinitiva(MotivoFalhaEfetivacao.LIMITE_VIGENTE_DIVERGENTE);
            case COD_RET_INSTRUCAO_INVALIDA -> new ResultadoConsultaStatusCore.FalhaDefinitiva(MotivoFalhaEfetivacao.INSTRUCAO_INVALIDA);
            default -> indeterminada("COD-RET desconhecido do CoreLegado na consulta de status: " + body.codRet());
        };
    }

    private static ResultadoConsultaStatusCore traduzirSucesso(ConsultaStatusEfetivacaoLegadoResponse body) {
        if (body.numPrt() == null || body.numPrt().isBlank() || body.vlrLimEft() == null || body.vlrLimEft().isBlank()) {
            return indeterminada("CoreLegado confirmou sucesso na consulta de status sem numPrt/vlrLimEft");
        }
        long limiteEfetivadoCentavos;
        try {
            limiteEfetivadoCentavos = Long.parseLong(body.vlrLimEft().trim());
        } catch (NumberFormatException e) {
            return indeterminada("vlrLimEft ilegivel na consulta de status: " + body.vlrLimEft());
        }
        // limiteEfetivadoCentavos <= 0 nao lanca aqui -- vira Indeterminada, nunca escapa como
        // IllegalArgumentException do compact constructor de Efetivada (contrato desta ACL: nunca
        // lanca excecao para o chamador).
        if (limiteEfetivadoCentavos <= 0) {
            return indeterminada("vlrLimEft nao positivo na consulta de status: " + body.vlrLimEft());
        }
        return new ResultadoConsultaStatusCore.Efetivada(new ProtocoloCore(body.numPrt().trim()), limiteEfetivadoCentavos);
    }

    private static ResultadoConsultaStatusCore.Indeterminada indeterminada(String detalhe) {
        return new ResultadoConsultaStatusCore.Indeterminada(detalhe);
    }
}
