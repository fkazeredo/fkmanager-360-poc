package com.fkmanager360.simuladorcorelegado.domain;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Estado em memoria das efetivacoes recebidas (plano #0004, secao 7): deduplicacao funcional por
 * {@code idEft} -- a MESMA instrucao reenviada devolve o MESMO {@code numPrt}, e {@code idEft}
 * existente com payload diferente e sempre rejeitado explicitamente, nunca tratado como operacao
 * nova. Essa deduplicacao e comportamento funcional do Core (spec, secao "EfetivacaoId, instrucao
 * e ProtocoloCore"), por isso vive aqui -- distinta do control plane abaixo.
 *
 * <p>Sem persistencia real (o CoreLegado e simulado, ADR-0002): um restart do container perde as
 * efetivacoes registradas, aceitavel para esta POC.
 */
@Component
public class EfetivacoesLegadoStore {

    public record RegistroEfetivacao(String numCta, String vlrLimChqEspEsp, String vlrLimNov, String numPrt) {
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
    private final Map<String, CenarioEfetivacao> cenariosPorConta = new ConcurrentHashMap<>();
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
     * agenda/processa a efetivacao).
     */
    public ResultadoRegistroAceite registrarAceite(String idEft, String numCta, String vlrLimChqEspEsp, String vlrLimNov) {
        AtomicBoolean criadoAgora = new AtomicBoolean(false);
        RegistroEfetivacao registro = aceitesPorIdEft.computeIfAbsent(idEft, id -> {
            criadoAgora.set(true);
            return new RegistroEfetivacao(
                    numCta, vlrLimChqEspEsp, vlrLimNov, "%012d".formatted(proximoNumeroDeProtocolo.getAndIncrement()));
        });
        return new ResultadoRegistroAceite(registro, criadoAgora.get());
    }

    // --- Control plane (perfis local/demo/test, ADR-0018) -------------------------------------

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
