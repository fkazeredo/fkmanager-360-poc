package com.fkmanager360.simuladorcorelegado.cliente;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Dataset determinístico em memória do simulador. Sem persistência: a consulta de dados mestres
 * neste ticket é somente-leitura, e o simulador não precisa sobreviver a restart com estado
 * próprio (ele nunca é dono de estado -- CoreLegado é o system of record, ADR-0002 -- este
 * container só o representa).
 *
 * <p>Os codigos abaixo espelham os clientes semeados pela migracao de demonstracao de
 * {@code servico-carteira-clientes} (carteiras dos gerentes A e B), para que a jornada vertical
 * completa seja coerente ponta a ponta.
 */
@Component
public class BaseClientesLegado {

    private final Map<String, RegistroClienteLegado> registros = new LinkedHashMap<>();

    public BaseClientesLegado() {
        // Carteira do gerente A: sete clientes, o suficiente para forcar paginacao com tamanho
        // de pagina 5 (AC22/AC7 da spec: "a listagem continua utilizavel quando crescer").
        semear("0000000001", "ANA BEATRIZ SOUZA", "11122233396", "01", "20180312");
        semear("0000000002", "CARLOS EDUARDO LIMA", "22233344497", "01", "20190704");
        semear("0000000003", "DANIELA FERREIRA COSTA", "33344455508", "01", "20200115");
        semear("0000000004", "EDUARDO HENRIQUE ROCHA", "44455566619", "01", "20170822");
        semear("0000000005", "FERNANDA ALMEIDA DIAS", "55566677720", "01", "20211003");
        semear("0000000006", "GUSTAVO MARTINS PEREIRA", "66677788831", "01", "20160530");
        semear("0000000007", "HELOISA CARDOSO NUNES", "77788899942", "01", "20220218");

        // Carteira do gerente B: tres clientes, exclusivos -- prova de segregacao (AC22/AC9).
        semear("0000000101", "IGOR BARBOSA TEIXEIRA", "10120230456", "01", "20190912");
        semear("0000000102", "JULIANA RIBEIRO MOURA", "20230340567", "01", "20200705");
        semear("0000000103", "KAUAN SANTOS AZEVEDO", "30340450678", "01", "20210228");

        // Cliente sem vinculo de carteira alguma -- existe no Core, nao aparece em nenhuma
        // listagem, util para provar que a associacao (nao a existencia no Core) governa acesso.
        semear("0000000999", "LARISSA PINTO MENEZES", "40450560789", "01", "20150110");
    }

    private void semear(String codCli, String nomCli, String numCpf, String sitCad, String datCad) {
        registros.put(codCli, new RegistroClienteLegado(codCli, nomCli, numCpf, sitCad, datCad));
    }

    public Optional<RegistroClienteLegado> buscar(String codCli) {
        return Optional.ofNullable(registros.get(codCli));
    }
}
