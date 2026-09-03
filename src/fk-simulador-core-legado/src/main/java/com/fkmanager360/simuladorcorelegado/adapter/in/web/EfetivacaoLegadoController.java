package com.fkmanager360.simuladorcorelegado.adapter.in.web;

import com.fkmanager360.simuladorcorelegado.domain.ContaLegadoRecord;
import com.fkmanager360.simuladorcorelegado.domain.ContasLegadoStore;
import com.fkmanager360.simuladorcorelegado.domain.EfetivacoesLegadoStore;
import com.fkmanager360.simuladorcorelegado.domain.EfetivacoesLegadoStore.DecisaoDeTransporte;
import com.fkmanager360.simuladorcorelegado.domain.EfetivacoesLegadoStore.RegistroEfetivacao;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * A operacao funcional de efetivacao (spec, secao "Contrato do simulador-core-legado"; plano
 * #0004, secao 7): recepcao da instrucao com deduplicacao por {@code idEft} e devolucao de
 * {@code numPrt}. Nao aplica a alteracao de fato no {@link ContasLegadoStore} -- confirmacao e
 * callback pertencem a #0005; consulta de status a #0006.
 *
 * <p>Sem autenticacao, pela mesma decisao consciente registrada em {@code ClienteLegadoController}.
 */
@RestController
@RequiredArgsConstructor
public class EfetivacaoLegadoController {

    private final EfetivacoesLegadoStore store;
    private final ContasLegadoStore contasStore;

    @PostMapping(path = "/legado/efetivacoes")
    public ResponseEntity<EfetivacaoLegadoResponse> efetivar(@Valid @RequestBody EfetivacaoLegadoRequest requisicao) {
        // Dedup por idEft PRECEDE o consumo do cenario de control-plane, nao o contrario: um
        // reenvio de idEft ja aceito e sempre idempotente (AC11), mesmo que um cenario de
        // indisponibilidade tenha sido armado para a mesma conta depois do aceite original --
        // caso contrario o reenvio consumiria o cenario e devolveria 503 espurio, quebrando a
        // garantia "reenvio nunca falha" documentada no contrato.
        Optional<RegistroEfetivacao> existente = store.buscarAceite(requisicao.idEft());
        if (existente.isPresent()) {
            return ResponseEntity.ok(payloadCompativel(existente.get(), requisicao)
                    ? EfetivacaoLegadoResponse.aceite(requisicao, existente.get().numPrt())
                    : EfetivacaoLegadoResponse.payloadIncompativel(requisicao));
        }

        DecisaoDeTransporte decisao = store.consumirCenario(requisicao.numCta());

        if (decisao instanceof DecisaoDeTransporte.Responder503Registrando) {
            store.registrarAceite(requisicao.idEft(), requisicao.numCta(), requisicao.vlrLimChqEspEsp(), requisicao.vlrLimNov());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        if (decisao instanceof DecisaoDeTransporte.Responder503SemRegistrar) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }

        Optional<ContaLegadoRecord> conta = contasStore.findByNumCta(requisicao.numCta());
        if (conta.isEmpty()) {
            return ResponseEntity.ok(EfetivacaoLegadoResponse.contaNaoEncontrada(requisicao));
        }
        // Qualquer sitCta diferente de "01" (regular) e tratada como bloqueio para efeito de
        // efetivacao -- fail-safe na direcao certa, mesmo criterio ja usado pela ACL de Credito
        // para SituacaoConta (nao existe "pior caso seguro" alem de recusar).
        if (!"01".equals(conta.get().sitCta())) {
            return ResponseEntity.ok(EfetivacaoLegadoResponse.contaBloqueada(requisicao));
        }
        if (Long.parseLong(conta.get().vlrLimChqEsp()) != Long.parseLong(requisicao.vlrLimChqEspEsp())) {
            return ResponseEntity.ok(EfetivacaoLegadoResponse.limiteVigenteDivergente(requisicao));
        }
        if (Long.parseLong(requisicao.vlrLimNov()) <= Long.parseLong(requisicao.vlrLimChqEspEsp())) {
            return ResponseEntity.ok(EfetivacaoLegadoResponse.instrucaoInvalida(requisicao));
        }

        RegistroEfetivacao novo = store.registrarAceite(
                requisicao.idEft(), requisicao.numCta(), requisicao.vlrLimChqEspEsp(), requisicao.vlrLimNov());
        return ResponseEntity.ok(EfetivacaoLegadoResponse.aceite(requisicao, novo.numPrt()));
    }

    private static boolean payloadCompativel(RegistroEfetivacao registro, EfetivacaoLegadoRequest requisicao) {
        return registro.numCta().equals(requisicao.numCta())
                && registro.vlrLimChqEspEsp().equals(requisicao.vlrLimChqEspEsp())
                && registro.vlrLimNov().equals(requisicao.vlrLimNov());
    }
}
