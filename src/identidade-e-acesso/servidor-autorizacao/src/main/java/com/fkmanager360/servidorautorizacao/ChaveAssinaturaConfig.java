package com.fkmanager360.servidorautorizacao;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.UUID;

/**
 * Chave de assinatura dos tokens. Em producao/demo, vem de PEM configurado por ambiente (par
 * gerado e mantido fora do repositorio -- nunca commitado, ver .env.example); sem ela, gera uma
 * chave efemera por instancia, valida apenas para desenvolvimento local e teste, onde invalidar
 * tokens a cada restart e aceitavel.
 */
@Configuration
public class ChaveAssinaturaConfig {

    private static final Logger LOG = LoggerFactory.getLogger(ChaveAssinaturaConfig.class);

    @Bean
    JWKSource<SecurityContext> jwkSource(
            @Value("${servidor-autorizacao.chave-assinatura.privada:}") String pemPrivada,
            @Value("${servidor-autorizacao.chave-assinatura.publica:}") String pemPublica) {

        RSAKey rsaKey = (pemPrivada.isBlank() || pemPublica.isBlank())
                ? gerarChaveEfemera()
                : carregarDePem(pemPrivada, pemPublica);

        return new ImmutableJWKSet<>(new JWKSet(rsaKey));
    }

    private RSAKey carregarDePem(String pemPrivada, String pemPublica) {
        try {
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");

            byte[] bytesPrivada = extrairBase64(pemPrivada);
            RSAPrivateKey privateKey = (RSAPrivateKey) keyFactory
                    .generatePrivate(new PKCS8EncodedKeySpec(bytesPrivada));

            byte[] bytesPublica = extrairBase64(pemPublica);
            RSAPublicKey publicKey = (RSAPublicKey) keyFactory
                    .generatePublic(new X509EncodedKeySpec(bytesPublica));

            return new RSAKey.Builder(publicKey)
                    .privateKey(privateKey)
                    .keyID(UUID.randomUUID().toString())
                    .build();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("Chave de assinatura configurada e invalida", e);
        }
    }

    private static byte[] extrairBase64(String pem) {
        String semLinhasDeCabecalho = pem
                .replace("\\n", "\n")
                .replaceAll("-----BEGIN [A-Z ]+-----", "")
                .replaceAll("-----END [A-Z ]+-----", "");
        return Base64.getMimeDecoder().decode(semLinhasDeCabecalho);
    }

    private RSAKey gerarChaveEfemera() {
        LOG.warn("Nenhuma chave de assinatura configurada -- gerando par RSA efemero. "
                + "Valido apenas para desenvolvimento local e teste: tokens ficam invalidos a cada restart.");
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();

            return new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                    .privateKey((RSAPrivateKey) keyPair.getPrivate())
                    .keyID(UUID.randomUUID().toString())
                    .build();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
