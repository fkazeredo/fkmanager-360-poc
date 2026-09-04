package com.fkmanager360.simuladorcorelegado.adapter.in.web;

import com.fkmanager360.simuladorcorelegado.domain.EfetivacoesLegadoStore;
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

/**
 * Control plane de cenarios de efetivacao (ADR-0018; plano #0004, secao 7): deliberadamente
 * separado do contrato funcional -- ativo somente nos profiles {@code local}, {@code demo} e
 * {@code test}, nunca faz parte da interface que uma ACL real conheceria. Cobre apenas os dois
 * cenarios que os testes deste ticket exigem: aceite perdido (recuperavel pelo reenvio, AC11) e
 * indisponibilidade transitoria (AC28). Consulta de status/reconciliacao pertence a #0006.
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

    @DeleteMapping("/{numCta}")
    public ResponseEntity<Void> limparCenario(@PathVariable String numCta) {
        store.limparCenario(numCta);
        return ResponseEntity.noContent().build();
    }
}
