package com.fkmanager360.credito.adapter.out.legacy;

import com.fkmanager360.credito.application.port.out.InstrucaoEfetivacaoCorePort;
import com.fkmanager360.credito.application.port.out.IntencaoEfetivacao;
import com.fkmanager360.credito.application.port.out.ResultadoInstrucaoCore;
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
 * ACL propria de Credito sobre a efetivacao do CoreLegado (ADR-0004; plano #0004, secao 6): unico
 * lugar que conhece {@code COD-RET}, {@code idEft}/{@code numPrt} e o formato host da instrucao de
 * efetivacao. Nunca lanca excecao para o chamador -- {@link InstrucaoEfetivacaoCorePort#entregar}
 * classifica toda patologia observavel nas quatro classes de {@link ResultadoInstrucaoCore}.
 *
 * <p><b>Regra de ouro (decisao do Owner):</b> erro HTTP tecnico nunca produz
 * {@link ResultadoInstrucaoCore.FalhaDefinitiva} pelo status -- somente um {@code COD-RET}
 * definitivo e conhecido produz essa classe. {@code 429} e classificado {@code TRANSITORIO}
 * (distinto do tratamento de {@code CoreLegadoCall}, que trata todo {@code HttpClientErrorException}
 * como resposta invalida -- essa ACL nao pode reusar aquela classificacao porque a taxonomia de
 * efetivacao exige a distincao). Qualquer outro {@code 4xx} sem semantica definida pelo contrato, e
 * qualquer resposta que esta ACL nao saiba interpretar (content-type incompativel via
 * {@code UnknownContentTypeException}, redirect que a request factory nao seguiu, JSON malformado),
 * cai no {@code RestClientException} generico abaixo e vira {@code INDETERMINADO} -- nunca
 * definitivo, nunca transitorio.
 *
 * <p>A CLASSIFICACAO de patologia (o que cada status/excecao produz) e deliberadamente reescrita
 * aqui, nao reusada de {@link CoreLegadoCall}: a taxonomia desta operacao diverge da leitura de
 * credito (429 e 4xx inesperado tem desfecho diferente aqui), e {@code CoreLegadoCall} nao pode
 * ser parametrizada sem acoplar as duas operacoes por uma abstracao que nenhuma das duas precisa
 * (ADR-0020 -- sem abstracao para o que nao e reusado de verdade). Ja a caminhada de causa-raiz
 * ({@link CoreLegadoCall#causeContains}) nao carrega taxonomia nenhuma -- e reusada, nao copiada.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EfetivacaoLegadoAclAdapter implements InstrucaoEfetivacaoCorePort {

    private static final String COD_RET_ACEITE = "000";
    private static final String COD_RET_CONTA_NAO_ENCONTRADA = "121";
    private static final String COD_RET_CONTA_BLOQUEADA = "118";
    private static final String COD_RET_LIMITE_VIGENTE_DIVERGENTE = "205";
    private static final String COD_RET_INSTRUCAO_INVALIDA = "199";
    private static final String COD_RET_PAYLOAD_INCOMPATIVEL = "207";
    private static final String COD_RET_INDISPONIVEL = "998";

    @Qualifier("efetivacaoLegadoRestClient")
    private final RestClient restClient;

    @Override
    public ResultadoInstrucaoCore entregar(IntencaoEfetivacao intencao) {
        EfetivacaoLegadoResponse body;
        try {
            body = restClient.post()
                    .uri("/legado/efetivacoes")
                    .body(EfetivacaoLegadoRequest.de(intencao))
                    .retrieve()
                    .body(EfetivacaoLegadoResponse.class);
        } catch (HttpClientErrorException.TooManyRequests e) {
            return transitoria("CoreLegado respondeu 429 (limite de taxa) ao efetivar");
        } catch (HttpServerErrorException e) {
            return transitoria("CoreLegado respondeu erro de servidor ao efetivar: " + e.getStatusCode());
        } catch (HttpClientErrorException e) {
            return indeterminada("CoreLegado recusou a requisicao de efetivacao com status inesperado: " + e.getStatusCode());
        } catch (RestClientException e) {
            if (CoreLegadoCall.causeContains(e, SocketTimeoutException.class)) {
                return transitoria("Timeout ao entregar instrucao de efetivacao ao CoreLegado");
            }
            if (CoreLegadoCall.causeContains(e, IOException.class)) {
                return transitoria("Falha de transporte ao entregar instrucao de efetivacao ao CoreLegado");
            }
            return indeterminada("Resposta do CoreLegado a efetivacao nao pode ser interpretada");
        }

        if (body == null || body.codRet() == null || body.codRet().isBlank()) {
            return indeterminada("CoreLegado devolveu resposta de efetivacao sem COD-RET");
        }

        return switch (body.codRet().trim()) {
            case COD_RET_ACEITE -> traduzirAceite(body);
            case COD_RET_CONTA_NAO_ENCONTRADA -> new ResultadoInstrucaoCore.FalhaDefinitiva(MotivoFalhaEfetivacao.CONTA_INEXISTENTE);
            case COD_RET_CONTA_BLOQUEADA -> new ResultadoInstrucaoCore.FalhaDefinitiva(MotivoFalhaEfetivacao.CONTA_BLOQUEADA_NA_EFETIVACAO);
            case COD_RET_LIMITE_VIGENTE_DIVERGENTE -> new ResultadoInstrucaoCore.FalhaDefinitiva(MotivoFalhaEfetivacao.LIMITE_VIGENTE_DIVERGENTE);
            case COD_RET_INSTRUCAO_INVALIDA -> new ResultadoInstrucaoCore.FalhaDefinitiva(MotivoFalhaEfetivacao.INSTRUCAO_INVALIDA);
            // Mesmo EfetivacaoId com payload incompativel: NAO e uma efetivacao nova, mas tambem
            // nao ha evidencia de que a efetivacao original falhou -- indeterminado com anomalia
            // (OD-3), nunca tratado como operacao nova nem como falha definitiva.
            case COD_RET_PAYLOAD_INCOMPATIVEL -> indeterminada("EfetivacaoId ja existente com payload incompativel (COD-RET 207)");
            case COD_RET_INDISPONIVEL -> transitoria("CoreLegado indisponivel (COD-RET 998)");
            default -> indeterminada("COD-RET desconhecido do CoreLegado na efetivacao: " + body.codRet());
        };
    }

    private static ResultadoInstrucaoCore traduzirAceite(EfetivacaoLegadoResponse body) {
        if (body.numPrt() == null || body.numPrt().isBlank()) {
            return indeterminada("CoreLegado aceitou a efetivacao sem NUM-PRT");
        }
        return new ResultadoInstrucaoCore.Aceite(new ProtocoloCore(body.numPrt().trim()));
    }

    private static ResultadoInstrucaoCore.FalhaTransitoria transitoria(String detalhe) {
        return new ResultadoInstrucaoCore.FalhaTransitoria(detalhe);
    }

    private static ResultadoInstrucaoCore.RespostaIndeterminada indeterminada(String detalhe) {
        return new ResultadoInstrucaoCore.RespostaIndeterminada(detalhe);
    }
}
