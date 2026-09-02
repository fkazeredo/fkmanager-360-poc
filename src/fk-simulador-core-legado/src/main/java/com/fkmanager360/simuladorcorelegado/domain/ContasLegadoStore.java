package com.fkmanager360.simuladorcorelegado.domain;

import org.springframework.stereotype.Component;

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
 */
@Component
public class ContasLegadoStore {

    private final Map<String, ContaLegadoRecord> records = new LinkedHashMap<>();

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

    public List<ContaLegadoRecord> findByCodCli(String codCli) {
        return records.values().stream().filter(conta -> conta.codCli().equals(codCli)).toList();
    }

    public Optional<ContaLegadoRecord> findByNumCta(String numCta) {
        return Optional.ofNullable(records.get(numCta));
    }
}
