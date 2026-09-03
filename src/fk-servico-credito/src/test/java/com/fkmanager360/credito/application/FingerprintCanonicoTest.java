package com.fkmanager360.credito.application;

import com.fkmanager360.credito.domain.ClienteId;
import com.fkmanager360.credito.domain.ContaId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S2 (aplicacao, sem Spring): o fingerprint e determinista e sensivel a cada campo relevante, e
 * ignora campos derivados no servidor (que nem chegam a este metodo).
 */
class FingerprintCanonicoTest {

    private static final ClienteId CLIENTE = new ClienteId("1");
    private static final ContaId CONTA = new ContaId("10001");

    private static String calcular(Long limiteSolicitado, Long limiteVigenteVisto, String canal, String observacao) {
        return FingerprintCanonico.calcular(CLIENTE, CONTA, limiteSolicitado, limiteVigenteVisto, canal, observacao);
    }

    @Test
    void mesmoComandoNormalizado_produzOMesmoFingerprint() {
        String primeiro = calcular(600_000L, 500_000L, "PRESENCIAL", "pediu no caixa");
        String segundo = calcular(600_000L, 500_000L, "PRESENCIAL", "pediu no caixa");

        assertThat(primeiro).isEqualTo(segundo);
    }

    @Test
    void limiteSolicitadoDiferente_produzFingerprintDiferente() {
        String base = calcular(600_000L, 500_000L, "PRESENCIAL", null);
        String alterado = calcular(600_001L, 500_000L, "PRESENCIAL", null);

        assertThat(base).isNotEqualTo(alterado);
    }

    @Test
    void limiteVigenteVistoDiferente_produzFingerprintDiferente() {
        String base = calcular(600_000L, 500_000L, "PRESENCIAL", null);
        String alterado = calcular(600_000L, 500_001L, "PRESENCIAL", null);

        assertThat(base).isNotEqualTo(alterado);
    }

    @Test
    void canalDiferente_produzFingerprintDiferente() {
        String base = calcular(600_000L, 500_000L, "PRESENCIAL", null);
        String alterado = calcular(600_000L, 500_000L, "TELEFONE", null);

        assertThat(base).isNotEqualTo(alterado);
    }

    @Test
    void observacaoDiferente_produzFingerprintDiferente() {
        String base = calcular(600_000L, 500_000L, "PRESENCIAL", "primeira observacao");
        String alterado = calcular(600_000L, 500_000L, "PRESENCIAL", "segunda observacao");

        assertThat(base).isNotEqualTo(alterado);
    }

    @Test
    void observacaoAusenteOuSoEspacos_produzemOMesmoFingerprint() {
        // "vazia apos trim equivale a ausente" -- tambem vale para o fingerprint, nao so para
        // ManifestacaoCliente: as duas formas representam exatamente o mesmo comando semantico.
        String semObservacao = calcular(600_000L, 500_000L, "PRESENCIAL", null);
        String comEspacos = calcular(600_000L, 500_000L, "PRESENCIAL", "    ");

        assertThat(semObservacao).isEqualTo(comEspacos);
    }

    @Test
    void observacaoComEspacosNasBordas_eEquivalenteAJaTratada() {
        String comEspacos = calcular(600_000L, 500_000L, "PRESENCIAL", "  ola  ");
        String jaTratada = calcular(600_000L, 500_000L, "PRESENCIAL", "ola");

        assertThat(comEspacos).isEqualTo(jaTratada);
    }

    @Test
    void canalComEspacosNasBordas_eEquivalenteAoNormalizado() {
        String comEspacos = calcular(600_000L, 500_000L, "  PRESENCIAL  ", null);
        String normalizado = calcular(600_000L, 500_000L, "PRESENCIAL", null);

        assertThat(comEspacos).isEqualTo(normalizado);
    }

    @Test
    void limiteSolicitadoAusente_naoLancaEProduzFingerprintProprio() {
        // O fingerprint precisa existir mesmo para um comando invalido: o pre-check de
        // idempotencia (passo 5) acontece ANTES das validacoes locais (passo 6).
        String comNulo = calcular(null, 500_000L, "PRESENCIAL", null);
        String comValor = calcular(600_000L, 500_000L, "PRESENCIAL", null);

        assertThat(comNulo).isNotNull().isNotEqualTo(comValor);
    }

    @Test
    void resultado_eHexadecimalDeSha256_64Caracteres() {
        String fingerprint = calcular(600_000L, 500_000L, "PRESENCIAL", null);
        assertThat(fingerprint).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void contaOuClienteDiferente_produzFingerprintDiferente() {
        String base = FingerprintCanonico.calcular(
                CLIENTE, CONTA, 600_000L, 500_000L, "PRESENCIAL", null);
        String outraConta = FingerprintCanonico.calcular(
                CLIENTE, new ContaId("99999"), 600_000L, 500_000L, "PRESENCIAL", null);

        assertThat(base).isNotEqualTo(outraConta);
    }
}
