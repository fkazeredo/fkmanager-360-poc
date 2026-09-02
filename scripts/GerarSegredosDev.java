import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.util.Base64;

/**
 * Helper de criptografia para scripts/gerar-segredos-dev.ps1. Roda via `java
 * GerarSegredosDev.java <modo> ...` (source-launch, sem compilacao separada). Existe porque a
 * API de criptografia do .NET Framework 4.x usada pelo Windows PowerShell 5.1 nao exporta PKCS8
 * (RSACng.ExportPkcs8PrivateKey nao existe nessa runtime) -- Java, ja garantido neste projeto,
 * resolve isso com API publica e estavel.
 */
public class GerarSegredosDev {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            throw new IllegalArgumentException("Uso: rsa-jwt <privPem> <pubPem> | export-p12 <p12> <senha> <alias> <certPem> <keyPem>");
        }

        switch (args[0]) {
            case "rsa-jwt" -> gerarParRsaParaJwt(args[1], args[2]);
            case "export-p12" -> exportarDoPkcs12(args[1], args[2], args[3], args[4], args[5]);
            default -> throw new IllegalArgumentException("Modo desconhecido: " + args[0]);
        }
    }

    /** Chave de assinatura de servidor-autorizacao: par RSA solto, sem certificado. */
    private static void gerarParRsaParaJwt(String caminhoPrivada, String caminhoPublica) throws Exception {
        KeyPairGenerator gerador = KeyPairGenerator.getInstance("RSA");
        gerador.initialize(2048);
        KeyPair par = gerador.generateKeyPair();

        escreverPem(caminhoPrivada, "PRIVATE KEY", par.getPrivate().getEncoded());
        escreverPem(caminhoPublica, "PUBLIC KEY", par.getPublic().getEncoded());
    }

    /** Certificado TLS do nginx: o par e o certificado ja existem num keystore gerado por keytool. */
    private static void exportarDoPkcs12(String caminhoP12, String senha, String alias, String caminhoCertPem, String caminhoKeyPem) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (var entrada = Files.newInputStream(Path.of(caminhoP12))) {
            keyStore.load(entrada, senha.toCharArray());
        }

        Certificate certificado = keyStore.getCertificate(alias);
        PrivateKey chavePrivada = (PrivateKey) keyStore.getKey(alias, senha.toCharArray());

        escreverPem(caminhoCertPem, "CERTIFICATE", certificado.getEncoded());
        escreverPem(caminhoKeyPem, "PRIVATE KEY", chavePrivada.getEncoded());
    }

    private static void escreverPem(String caminho, String tipo, byte[] conteudoDer) throws Exception {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(conteudoDer);
        try (PrintWriter writer = new PrintWriter(new FileOutputStream(caminho), false, java.nio.charset.StandardCharsets.US_ASCII)) {
            writer.print("-----BEGIN " + tipo + "-----\n");
            writer.print(base64);
            writer.print("\n-----END " + tipo + "-----\n");
        }
    }
}
