package com.fkmanager360.credito.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AC27: canal obrigatorio e restrito; observacao opcional, com trim, recusada acima de 500
 * caracteres, e vazia apos trim equivalente a ausencia.
 */
class ManifestacaoClienteTest {

    @Test
    void canalManifestacao_eObrigatorio() {
        assertThatThrownBy(() -> new ManifestacaoCliente(null, "qualquer coisa"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void observacaoAusente_permaneceNula() {
        var manifestacao = new ManifestacaoCliente(CanalManifestacao.PRESENCIAL, null);
        assertThat(manifestacao.observacao()).isNull();
    }

    @Test
    void observacao_sofreTrim() {
        var manifestacao = new ManifestacaoCliente(CanalManifestacao.TELEFONE, "  pediu por telefone  ");
        assertThat(manifestacao.observacao()).isEqualTo("pediu por telefone");
    }

    @Test
    void observacaoEmBranco_apósTrimViraAusencia() {
        var manifestacao = new ManifestacaoCliente(CanalManifestacao.CANAL_DIGITAL, "     ");
        assertThat(manifestacao.observacao()).isNull();
    }

    @Test
    void observacaoComExatamente500Caracteres_eAceita() {
        String observacao = "a".repeat(500);
        var manifestacao = new ManifestacaoCliente(CanalManifestacao.PRESENCIAL, observacao);
        assertThat(manifestacao.observacao()).hasSize(500);
    }

    @Test
    void observacaoComQuinhentosEUmCaracteres_eRecusada() {
        String observacao = "a".repeat(501);
        assertThatThrownBy(() -> new ManifestacaoCliente(CanalManifestacao.PRESENCIAL, observacao))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void limiteDeTamanhoEAplicado_apósOTrim() {
        // 500 caracteres uteis mais espacos nas bordas: apos o trim, cabe -- a checagem de tamanho
        // e sobre o texto tratado, nao sobre o bruto recebido.
        String comEspacos = " " + "a".repeat(500) + " ";
        var manifestacao = new ManifestacaoCliente(CanalManifestacao.PRESENCIAL, comEspacos);
        assertThat(manifestacao.observacao()).hasSize(500);
    }
}
