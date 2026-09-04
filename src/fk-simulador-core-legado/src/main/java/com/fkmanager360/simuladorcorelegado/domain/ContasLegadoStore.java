package com.fkmanager360.simuladorcorelegado.domain;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Dataset deterministico em memoria das contas correntes, no mesmo espirito de
 * {@link ClientesLegadoStore}: o CoreLegado e o system of record (ADR-0002), e este container
 * apenas o representa.
 *
 * <p>Cada conta pertence a um dos clientes ja semeados em {@link ClientesLegadoStore}, para que a
 * jornada vertical continue coerente ponta a ponta. Os valores de limite, situacao e risco variam
 * deliberadamente entre as contas -- sao dados de demonstracao, nao um desenho de cenarios de
 * politica de credito, que nem existe neste ticket.
 *
 * <p>{@code Collections.synchronizedMap} sobre {@code LinkedHashMap}, e nao
 * {@code ConcurrentHashMap}, desde #0005: {@link #aplicarLimiteChequeEspecial} muta um registro
 * apos o processamento assincrono da efetivacao, concorrentemente com leituras de
 * {@link #findByNumCta} -- mas {@code findByCodCli} depende da ORDEM DE INSERCAO do seed (prova
 * ja existente em {@code ContaLegadoControllerTest}), que {@code LinkedHashMap} preserva e um
 * {@code put} sobre chave EXISTENTE nao altera.
 */
@Component
public class ContasLegadoStore {

    private final Map<String, ContaLegadoRecord> records = Collections.synchronizedMap(new LinkedHashMap<>());

    public ContasLegadoStore() {
        // Carteira do gerente A. O cliente 0000000001 tem duas contas: sem isso, "escolher a
        // conta certa antes de qualquer solicitacao" nao seria uma escolha de verdade.
        seed("0000010001", "0001", "0000000001", "01", "000000000500000", "1", "20260115");
        seed("0000010002", "0001", "0000000001", "01", "000000000120000", "2", "20250820");
        seed("0000010003", "0001", "0000000002", "01", "000000001000000", "2", "20251103");
        seed("0000010004", "0002", "0000000003", "01", "000000000250000", "1", "20260210");
        seed("0000010005", "0002", "0000000004", "02", "000000000300000", "3", "20240517");
        seed("0000010006", "0002", "0000000005", "01", "000000000000000", "1", "20260301");
        seed("0000010007", "0003", "0000000006", "01", "000000000750000", "3", "20250712");
        seed("0000010008", "0003", "0000000007", "03", "000000000180000", "2", "20230929");

        // Carteira do gerente B: contas exclusivas, para que a segregacao continue verificavel
        // tambem no nivel da conta, e nao so no da listagem de clientes.
        seed("0000020001", "0004", "0000000101", "01", "000000000450000", "1", "20260122");
        seed("0000020002", "0004", "0000000102", "01", "000000000600000", "2", "20251209");
        seed("0000020003", "0005", "0000000103", "01", "000000000900000", "3", "20250405");

        // Conta do cliente sem vinculo de carteira alguma: existe no Core e nunca deve ser
        // alcancavel por nenhum gerente -- a associacao concede acesso, a existencia nao.
        seed("0000090001", "0009", "0000000999", "01", "000000000200000", "1", "20260228");
    }

    private void seed(String numCta, String codAge, String codCli,
                      String sitCta, String vlrLimChqEsp, String codRscCrd, String datAtuLim) {
        records.put(numCta, new ContaLegadoRecord(numCta, codAge, codCli, sitCta, vlrLimChqEsp, codRscCrd, datAtuLim));
    }

    /**
     * {@code synchronized} explicito, nao so o wrapper de {@link #records}: o contrato de
     * {@link Collections#synchronizedMap} exige sincronizar manualmente na propria trava do mapa
     * para ITERAR sobre qualquer uma das suas views (aqui, {@code values().stream()}) -- sem isso,
     * a iteracao concorrente com {@link #aplicarLimiteChequeEspecial} nao tem happens-before
     * garantido pela JLS, mesmo com o wrapper sincronizando cada chamada individual.
     */
    public List<ContaLegadoRecord> findByCodCli(String codCli) {
        synchronized (records) {
            return records.values().stream().filter(conta -> conta.codCli().equals(codCli)).toList();
        }
    }

    public Optional<ContaLegadoRecord> findByNumCta(String numCta) {
        return Optional.ofNullable(records.get(numCta));
    }

    /**
     * #0005: aplica de fato o novo {@code LimiteChequeEspecial} apos o processamento assincrono
     * da efetivacao -- e o que faz {@link #findByNumCta} (consultado por
     * {@code /legado/contas/consulta-credito}, a ACL de Credito) refletir o vigente novo depois da
     * confirmacao. {@code computeIfPresent} substitui so o VALOR de uma chave ja existente -- nao
     * e modificacao estrutural do mapa, entao nao interfere com iteracao concorrente em
     * {@link #findByCodCli}. Conta desconhecida (nunca deveria acontecer -- so quem ja teve a
     * conta validada no aceite chega aqui) e silenciosamente ignorada.
     */
    public void aplicarLimiteChequeEspecial(String numCta, String novoVlrLimChqEsp) {
        records.computeIfPresent(numCta, (chave, atual) -> new ContaLegadoRecord(
                atual.numCta(), atual.codAge(), atual.codCli(), atual.sitCta(), novoVlrLimChqEsp, atual.codRscCrd(), atual.datAtuLim()));
    }
}
