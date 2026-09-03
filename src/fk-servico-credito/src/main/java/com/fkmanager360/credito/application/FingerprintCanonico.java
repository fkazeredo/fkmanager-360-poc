package com.fkmanager360.credito.application;

import com.fkmanager360.credito.domain.ClienteId;
import com.fkmanager360.credito.domain.ContaId;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Fingerprint canonico do comando de submissao (spec, secao "Idempotencia da submissao"; plano
 * #0003, secao "Fingerprint semantico e canonico"). Opera sobre o COMANDO recebido, ja
 * normalizado, e nao sobre o {@code ContextoDecisaoCredito} -- que so passa a existir depois da
 * consulta ao CoreLegado, varios passos depois de o fingerprint precisar existir (passo 4 da Fase
 * 0, antes do passo 5 de consulta a idempotencia). Por isso vive em {@code application}, como
 * parte do mecanismo de idempotencia, e nao em {@code domain}.
 *
 * <p>Formula (ordem fixa, campos derivados no servidor como {@code origemSolicitacao},
 * {@code solicitacaoId}, {@code correlationId} e {@code versaoPoliticaCredito} NAO entram):
 *
 * <pre>
 * v1|clienteId=&lt;canon&gt;|contaId=&lt;canon&gt;|limiteSolicitado=&lt;centavos&gt;
 *   |limiteVigenteVisto=&lt;centavos&gt;|canalManifestacao=&lt;bruto normalizado&gt;|observacao=&lt;len&gt;:&lt;texto ou -&gt;
 * </pre>
 *
 * <p>SHA-256 em hexadecimal. O prefixo {@code "v1|"} e a versao do ALGORITMO de fingerprint --
 * nao da PoliticaCredito -- para que evoluir a formula no futuro nunca case silenciosamente com
 * registros gravados sob a formula antiga. {@code observacao} e length-prefixed exatamente para
 * que nenhum texto de observacao possa forjar o separador {@code "|"} e produzir uma colisao
 * deliberada; ausente (nulo, ou vazio apos trim) vira o literal {@code "-"}, sem prefixo de
 * tamanho algum, o que a distingue de qualquer observacao real (mesmo uma string vazia nao chega
 * aqui vazia -- {@code ManifestacaoCliente} ja normaliza vazio-apos-trim para ausencia).
 *
 * <p>Os valores numericos sao recebidos como {@link Long} (nao {@code long}) deliberadamente:
 * o fingerprint precisa ser calculavel ANTES das validacoes locais (passo 4 antes do passo 6), e
 * um comando semanticamente invalido -- por exemplo {@code limiteSolicitado} ausente -- ainda
 * precisa de um fingerprint deterministico para que o pre-check de idempotencia funcione sobre
 * ele. {@code null} formata-se como o literal {@code "null"}, distinguivel de qualquer numero.
 */
public final class FingerprintCanonico {

    private FingerprintCanonico() {
    }

    public static String calcular(
            ClienteId clienteId,
            ContaId contaId,
            Long limiteSolicitadoCentavos,
            Long limiteVigenteVistoCentavos,
            String canalManifestacaoBruto,
            String observacaoBruta) {
        Objects.requireNonNull(clienteId, "clienteId e obrigatorio");
        Objects.requireNonNull(contaId, "contaId e obrigatorio");

        String canalNormalizado = canalManifestacaoBruto == null ? "" : canalManifestacaoBruto.trim();
        String observacaoTratada = observacaoBruta == null ? null : observacaoBruta.trim();
        String observacaoCampo = (observacaoTratada == null || observacaoTratada.isEmpty())
                ? "-"
                : observacaoTratada.length() + ":" + observacaoTratada;

        String canonico = "v1|clienteId=" + clienteId.valor()
                + "|contaId=" + contaId.valor()
                + "|limiteSolicitado=" + limiteSolicitadoCentavos
                + "|limiteVigenteVisto=" + limiteVigenteVistoCentavos
                + "|canalManifestacao=" + canalNormalizado
                + "|observacao=" + observacaoCampo;

        return sha256Hex(canonico);
    }

    private static String sha256Hex(String texto) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(texto.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponivel na JVM", e);
        }
    }
}
