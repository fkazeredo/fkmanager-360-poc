package com.fkmanager360.credito.application.port.out;

import com.fkmanager360.credito.domain.StatusSolicitacaoAumentoLimite;

import java.time.Duration;
import java.util.Objects;

/**
 * Resultado de {@link ResultadoEfetivacaoPort#registrar} (#0005): a classificacao terminal
 * verifica, nesta ordem, tres eixos -- protocolo, resultado/motivo, limite efetivado quando
 * sucesso -- e so retorna {@link JaTerminalIdentica} quando os tres coincidem com o que ja esta
 * persistido; qualquer divergencia em qualquer eixo produz {@link JaTerminalContraditoria}. Um
 * protocolo divergente NUNCA e tratado como duplicado, mesmo com status/motivo/valor coincidentes
 * (ver Javadoc de {@link JaTerminalIdentica}).
 *
 * <p>Sealed em vez do record unico de #0004 porque #0005 introduz patologias que uma transicao
 * booleana ("concluiu agora?") nao distingue: duplicado idêntico, contradicao sobre terminal,
 * sucesso incoerente (AC26) e protocolo divergente em nao-terminal (spec, bullet "protocolo
 * contraditorio") sao desfechos semanticamente distintos, cada um com sua propria consequencia
 * observavel (metrica de anomalia, codigo de resposta do callback).
 */
public sealed interface ResultadoRegistroEfetivacao {

    /** A chamada aplicou a transicao agora -- unico caso em que a permanencia em AGUARDANDO_EFETIVACAO existe. */
    record Concluida(StatusSolicitacaoAumentoLimite statusResultante, Duration permanenciaEmAguardandoEfetivacao)
            implements ResultadoRegistroEfetivacao {
        public Concluida {
            Objects.requireNonNull(statusResultante, "statusResultante e obrigatorio");
            Objects.requireNonNull(permanenciaEmAguardandoEfetivacao, "permanenciaEmAguardandoEfetivacao e obrigatoria");
        }
    }

    /**
     * Ja terminal, e o resultado recebido agora e coerente em protocolo, resultado/motivo e (se
     * sucesso) limite efetivado com o que ja esta persistido (AC13 -- duplicado identico). Nao
     * escreve nada; nao produz nova entrada de historico.
     */
    record JaTerminalIdentica(StatusSolicitacaoAumentoLimite statusPersistido) implements ResultadoRegistroEfetivacao {
        public JaTerminalIdentica {
            Objects.requireNonNull(statusPersistido, "statusPersistido e obrigatorio");
        }
    }

    /**
     * Ja terminal, mas o resultado recebido agora diverge em pelo menos um dos tres eixos do
     * persistido (AC17 -- contraditorio sobre estado terminal). Nao reescreve o estado, nao
     * inventa transicao no historico; quem chama registra a anomalia observavel.
     */
    record JaTerminalContraditoria(StatusSolicitacaoAumentoLimite statusPersistido) implements ResultadoRegistroEfetivacao {
        public JaTerminalContraditoria {
            Objects.requireNonNull(statusPersistido, "statusPersistido e obrigatorio");
        }
    }

    /**
     * Nao-terminal, sucesso recebido mas {@code limiteEfetivadoCentavos} incompativel com o
     * {@code LimiteSolicitado} congelado (AC26). Nao transiciona, nao sobrescreve o resultado
     * esperado; a operacao permanece recuperavel por um resultado autoritativo coerente posterior.
     */
    record SucessoIncoerente() implements ResultadoRegistroEfetivacao {
    }

    /**
     * Nao-terminal, {@code ProtocoloCore} informado diverge do ja persistido para o mesmo
     * {@code EfetivacaoId} (spec, bullet "protocolo contraditorio"). O existente nunca e
     * sobrescrito; zero escrita.
     */
    record ProtocoloDivergente() implements ResultadoRegistroEfetivacao {
    }
}
