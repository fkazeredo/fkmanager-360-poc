package com.fkmanager360.simuladorcorelegado.domain;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Estado em memoria das efetivacoes recebidas (plano #0004, secao 7; #0006): deduplicacao funcional
 * por {@code idEft} -- a MESMA instrucao reenviada devolve o MESMO {@code numPrt}, e {@code idEft}
 * existente com payload diferente e sempre rejeitado explicitamente, nunca tratado como operacao
 * nova. Essa deduplicacao e comportamento funcional do Core (spec, secao "EfetivacaoId, instrucao
 * e ProtocoloCore"), por isso vive aqui -- distinta do control plane abaixo.
 *
 * <p>#0006 acrescenta o DESFECHO do processamento assincrono ({@link #marcarProcessada}), o indice
 * reverso {@code numPrt -> idEft} e o control plane de callback (suprimir/suspender) que a consulta
 * de status precisa para ser demonstravel.
 *
 * <p>Sem persistencia real (o CoreLegado e simulado, ADR-0002): um restart do container perde as
 * efetivacoes registradas, aceitavel para esta POC.
 */
@Component
public class EfetivacoesLegadoStore {

    public record RegistroEfetivacao(String numCta, String vlrLimChqEspEsp, String vlrLimNov, String numPrt) {
    }

    /** Desfecho consultavel de uma efetivacao ja aceita (#0006). */
    public sealed interface ResultadoConsultaStatus {
        record Processada(String idEft, String numPrt, String vlrLimEft) implements ResultadoConsultaStatus {
        }

        record EmProcessamento(String idEft, String numPrt) implements ResultadoConsultaStatus {
        }

        record Desconhecida() implements ResultadoConsultaStatus {
        }
    }

    /** Dados suficientes para retomar um processamento suspenso quando o control plane o liberar (#0006). */
    public record PendenciaProcessamento(String idEft, String numCta, String numPrt, String vlrLimNov, String idCor) {
    }

    /** Modo de callback armado para uma conta (#0006) -- distinto e independente do control plane de aceite abaixo. */
    public enum ModoCallback {
        NORMAL, SUPRIMIR, SUSPENDER
    }

    /**
     * O control plane (ADR-0018) e deliberadamente separado do contrato funcional: nao e
     * capacidade do CoreLegado, e existe apenas para os testes deste ticket simularem patologias
     * de transporte que dados estaticos nao produzem sozinhos.
     */
    private sealed interface CenarioEfetivacao {
        record PerderAceite() implements CenarioEfetivacao {
        }

        record Indisponivel(int restantes) implements CenarioEfetivacao {
        }
    }

    public sealed interface DecisaoDeTransporte {
        record Prosseguir() implements DecisaoDeTransporte {
        }

        /** O aceite E registrado (numPrt emitido) mas a RESPOSTA se perde -- recupera-se pelo reenvio (AC11). */
        record Responder503Registrando() implements DecisaoDeTransporte {
        }

        /** Indisponibilidade transitoria genuina: nada e registrado. */
        record Responder503SemRegistrar() implements DecisaoDeTransporte {
        }
    }

    private final Map<String, RegistroEfetivacao> aceitesPorIdEft = new ConcurrentHashMap<>();
    private final Map<String, String> idEftPorNumPrt = new ConcurrentHashMap<>();
    private final Map<String, String> vlrLimEftPorIdEftProcessada = new ConcurrentHashMap<>();
    private final Map<String, CenarioEfetivacao> cenariosPorConta = new ConcurrentHashMap<>();
    private final Map<String, ModoCallback> modoCallbackPorConta = new ConcurrentHashMap<>();
    private final Map<String, PendenciaProcessamento> pendenciasPorConta = new ConcurrentHashMap<>();
    private final AtomicLong proximoNumeroDeProtocolo = new AtomicLong(1);

    /**
     * {@code criadoAgora=true} quando ESTA chamada foi quem criou o registro -- so ela deve
     * agendar/processar a efetivacao (#0005: mutar {@code ContasLegadoStore} e disparar o
     * callback). As demais chamadas concorrentes para o mesmo {@code idEft} (dedup) observam
     * {@code criadoAgora=false} e nao devem processar de novo.
     */
    public record ResultadoRegistroAceite(RegistroEfetivacao registro, boolean criadoAgora) {
    }

    public Optional<RegistroEfetivacao> buscarAceite(String idEft) {
        return Optional.ofNullable(aceitesPorIdEft.get(idEft));
    }

    /**
     * Atomico sob concorrencia real: {@code numPrt} e gerado uma UNICA vez por {@code idEft}, e
     * {@code criadoAgora} e {@code true} para NO MAXIMO uma chamada concorrente para o mesmo
     * {@code idEft} -- {@link ConcurrentHashMap#computeIfAbsent} garante que a funcao de mapeamento
     * roda no maximo uma vez por chave, entao o {@link AtomicBoolean} local so e setado {@code true}
     * na execucao que efetivamente criou o registro (#0005, guardrail do Owner: so essa execucao
     * agenda/processa a efetivacao). O indice reverso {@code numPrt -> idEft} (#0006) nasce dentro
     * da MESMA funcao de mapeamento -- nunca ha numPrt sem entrada no indice.
     */
    public ResultadoRegistroAceite registrarAceite(String idEft, String numCta, String vlrLimChqEspEsp, String vlrLimNov) {
        AtomicBoolean criadoAgora = new AtomicBoolean(false);
        RegistroEfetivacao registro = aceitesPorIdEft.computeIfAbsent(idEft, id -> {
            criadoAgora.set(true);
            String numPrt = "%012d".formatted(proximoNumeroDeProtocolo.getAndIncrement());
            return new RegistroEfetivacao(numCta, vlrLimChqEspEsp, vlrLimNov, numPrt);
        });
        // Indice reverso povoado SO APOS aceitesPorIdEft ja estar visivel (nao dentro do
        // computeIfAbsent acima): caso contrario, uma consulta por numPrt concorrente poderia
        // encontrar o indice antes da entrada principal e reportar "desconhecida" para um idEft
        // que na verdade ja foi aceito.
        if (criadoAgora.get()) {
            idEftPorNumPrt.put(registro.numPrt(), idEft);
        }
        return new ResultadoRegistroAceite(registro, criadoAgora.get());
    }

    // --- Consulta de status (#0006) ------------------------------------------------------------

    /** Marca o desfecho do processamento assincrono: so a partir daqui a consulta de status responde "processada". */
    public void marcarProcessada(String idEft, String vlrLimEft) {
        vlrLimEftPorIdEftProcessada.put(idEft, vlrLimEft);
    }

    public ResultadoConsultaStatus consultarPorIdEft(String idEft) {
        RegistroEfetivacao registro = aceitesPorIdEft.get(idEft);
        if (registro == null) {
            return new ResultadoConsultaStatus.Desconhecida();
        }
        String vlrLimEft = vlrLimEftPorIdEftProcessada.get(idEft);
        return vlrLimEft != null
                ? new ResultadoConsultaStatus.Processada(idEft, registro.numPrt(), vlrLimEft)
                : new ResultadoConsultaStatus.EmProcessamento(idEft, registro.numPrt());
    }

    public ResultadoConsultaStatus consultarPorNumPrt(String numPrt) {
        String idEft = idEftPorNumPrt.get(numPrt);
        return idEft == null ? new ResultadoConsultaStatus.Desconhecida() : consultarPorIdEft(idEft);
    }

    // --- Control plane de callback (perfis local/demo/test, ADR-0018) -------------------------

    public void configurarSuprimirCallback(String numCta) {
        modoCallbackPorConta.put(numCta, ModoCallback.SUPRIMIR);
    }

    public void configurarSuspenderProcessamento(String numCta) {
        modoCallbackPorConta.put(numCta, ModoCallback.SUSPENDER);
    }

    public void limparModoCallback(String numCta) {
        modoCallbackPorConta.remove(numCta);
    }

    /**
     * {@code SUPRIMIR} e disparo UNICO -- consumido ao ser lido, como {@code PerderAceite} acima.
     * {@code SUSPENDER} permanece armado ate {@link #liberarPendencia} ser chamado explicitamente
     * pelo control plane: o processamento fica retido por tempo indeterminado, nao por uma unica
     * tentativa.
     */
    public ModoCallback consultarEConsumirModoCallback(String numCta) {
        AtomicReference<ModoCallback> resultado = new AtomicReference<>(ModoCallback.NORMAL);
        modoCallbackPorConta.compute(numCta, (conta, modo) -> {
            if (modo == null) {
                return null;
            }
            resultado.set(modo);
            return modo == ModoCallback.SUSPENDER ? modo : null;
        });
        return resultado.get();
    }

    /**
     * Uma UNICA pendencia suspensa por conta por vez: este control plane nunca precisou modelar
     * fila, e sobrescrever silenciosamente uma pendencia ainda nao liberada perderia o {@code idEft}
     * anterior para sempre (nem processado, nem mais recuperavel por {@link #liberarPendencia}).
     */
    public void registrarPendencia(String numCta, PendenciaProcessamento pendencia) {
        PendenciaProcessamento anterior = pendenciasPorConta.putIfAbsent(numCta, pendencia);
        if (anterior != null) {
            throw new IllegalStateException(
                    "Ja existe uma pendencia suspensa para numCta=%s (idEft=%s); libere-a antes de suspender outra"
                            .formatted(numCta, anterior.idEft()));
        }
    }

    /**
     * Libera o processamento suspenso: remove a pendencia e, SO quando ela de fato existia, o modo
     * {@code SUSPENDER} armado -- sem isso, o processamento retomado encontraria o modo ainda ativo
     * e se suspenderia de novo, indefinidamente. Chamar sobre uma conta sem pendencia (ja liberada,
     * ou nunca suspensa) e um no-op puro: nao deve apagar um {@code SUPRIMIR} ainda nao consumido.
     */
    public Optional<PendenciaProcessamento> liberarPendencia(String numCta) {
        Optional<PendenciaProcessamento> pendencia = Optional.ofNullable(pendenciasPorConta.remove(numCta));
        pendencia.ifPresent(ignored -> modoCallbackPorConta.remove(numCta));
        return pendencia;
    }

    /** Descarta uma pendencia suspensa nunca liberada (fim de cenario de teste, #0006). */
    public void limparPendencia(String numCta) {
        pendenciasPorConta.remove(numCta);
    }

    // --- Control plane de aceite (perfis local/demo/test, ADR-0018) ----------------------------

    public void configurarPerderAceite(String numCta) {
        cenariosPorConta.put(numCta, new CenarioEfetivacao.PerderAceite());
    }

    public void configurarIndisponivel(String numCta, int vezes) {
        cenariosPorConta.put(numCta, new CenarioEfetivacao.Indisponivel(vezes));
    }

    public void limparCenario(String numCta) {
        cenariosPorConta.remove(numCta);
    }

    /**
     * Consome atomicamente o cenario configurado para a conta. {@code PerderAceite} e um
     * disparo UNICO -- a proxima chamada para a mesma conta ja nao o aciona mais.
     * {@code Indisponivel(n)} decrementa a cada chamada e se esgota apos {@code n} disparos.
     */
    public DecisaoDeTransporte consumirCenario(String numCta) {
        AtomicReference<DecisaoDeTransporte> decisao = new AtomicReference<>(new DecisaoDeTransporte.Prosseguir());
        cenariosPorConta.compute(numCta, (conta, cenario) -> {
            if (cenario instanceof CenarioEfetivacao.PerderAceite) {
                decisao.set(new DecisaoDeTransporte.Responder503Registrando());
                return null;
            }
            if (cenario instanceof CenarioEfetivacao.Indisponivel indisponivelAtual) {
                if (indisponivelAtual.restantes() <= 0) {
                    // Configurado sem disparos restantes: "0 indisponibilidades" precisa
                    // significar nenhuma, nao uma -- so limpa o cenario, nunca aciona 503.
                    return null;
                }
                decisao.set(new DecisaoDeTransporte.Responder503SemRegistrar());
                int restantes = indisponivelAtual.restantes() - 1;
                return restantes > 0 ? new CenarioEfetivacao.Indisponivel(restantes) : null;
            }
            return cenario;
        });
        return decisao.get();
    }
}
