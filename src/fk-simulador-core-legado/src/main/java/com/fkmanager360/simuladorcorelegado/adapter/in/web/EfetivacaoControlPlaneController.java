package com.fkmanager360.simuladorcorelegado.adapter.in.web;

import com.fkmanager360.simuladorcorelegado.adapter.in.scheduling.ProcessadorEfetivacaoLegado;
import com.fkmanager360.simuladorcorelegado.domain.EfetivacoesLegadoStore;
import com.fkmanager360.simuladorcorelegado.domain.EfetivacoesLegadoStore.PendenciaProcessamento;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * Control plane de cenarios de efetivacao (ADR-0018; plano #0004, secao 7; #0006): deliberadamente
 * separado do contrato funcional -- ativo somente nos profiles {@code local}, {@code demo} e
 * {@code test}, nunca faz parte da interface que uma ACL real conheceria. Cobre aceite perdido
 * (recuperavel pelo reenvio, AC11), indisponibilidade transitoria (AC28), e -- desde #0006 --
 * callback suprimido (recuperado pela reconciliacao, AC12) e processamento suspenso (janela de
 * reconciliacao esgotada, AC16).
 *
 * <p>{@code @Hidden}: fora do OpenAPI gerado pela mesma razao -- o contrato funcional
 * ({@code openapi.yaml}, ADR-0019) descreve a interface do CoreLegado simulado, e o control plane
 * nao e capacidade do CoreLegado.
 */
@Hidden
@RestController
@RequestMapping("/control-plane/efetivacoes")
@RequiredArgsConstructor
@Profile({"local", "demo", "test"})
public class EfetivacaoControlPlaneController {

    private final EfetivacoesLegadoStore store;
    private final ProcessadorEfetivacaoLegado processador;

    /** Proxima chamada para {@code numCta} registra o aceite mas responde 503 -- disparo unico. */
    @PostMapping("/{numCta}/perder-aceite")
    public ResponseEntity<Void> configurarPerderAceite(@PathVariable String numCta) {
        store.configurarPerderAceite(numCta);
        return ResponseEntity.noContent().build();
    }

    /** As proximas {@code vezes} chamadas para {@code numCta} respondem 503 sem registrar nada. */
    @PostMapping("/{numCta}/indisponivel")
    public ResponseEntity<Void> configurarIndisponivel(
            @PathVariable String numCta, @RequestParam(defaultValue = "1") int vezes) {
        store.configurarIndisponivel(numCta, vezes);
        return ResponseEntity.noContent().build();
    }

    /**
     * #0006, AC12: o proximo processamento de {@code numCta} muda o limite e registra o desfecho
     * normalmente, mas o callback NUNCA e disparado -- so a reconciliacao recupera o resultado.
     * Disparo UNICO, consumido no processamento (ver {@code ProcessadorEfetivacaoLegado}).
     */
    @PostMapping("/{numCta}/suprimir-callback")
    public ResponseEntity<Void> configurarSuprimirCallback(@PathVariable String numCta) {
        store.configurarSuprimirCallback(numCta);
        return ResponseEntity.noContent().build();
    }

    /**
     * #0006, AC16: o proximo processamento de {@code numCta} fica retido -- nem o limite muda nem
     * o callback dispara -- ate {@link #liberarProcessamento} ser chamado explicitamente para a
     * MESMA conta. Habilita demonstrar a janela de reconciliacao esgotando (entrada em
     * {@code EFETIVACAO_INDETERMINADA}) seguida de conclusao tardia.
     */
    @PostMapping("/{numCta}/suspender-processamento")
    public ResponseEntity<Void> configurarSuspenderProcessamento(@PathVariable String numCta) {
        store.configurarSuspenderProcessamento(numCta);
        return ResponseEntity.noContent().build();
    }

    /**
     * #0006: libera um processamento suspenso por {@link #configurarSuspenderProcessamento} --
     * aplica o novo limite e dispara o callback normalmente (modo volta a NORMAL). {@code 404}
     * quando nao ha pendencia para {@code numCta} (nunca suspenso, ou ja liberado).
     */
    @PostMapping("/{numCta}/liberar")
    public ResponseEntity<Void> liberarProcessamento(@PathVariable String numCta) {
        Optional<PendenciaProcessamento> pendencia = store.liberarPendencia(numCta);
        if (pendencia.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        processador.processarPendenciaLiberada(pendencia.get());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{numCta}")
    public ResponseEntity<Void> limparCenario(@PathVariable String numCta) {
        store.limparCenario(numCta);
        store.limparModoCallback(numCta);
        store.limparPendencia(numCta);
        return ResponseEntity.noContent().build();
    }
}
