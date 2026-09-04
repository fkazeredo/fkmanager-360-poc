package com.fkmanager360.credito.config;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * JWT controlado (ADR-0018): assina tokens de teste com uma chave RSA gerada em memoria, sem
 * depender de servidor-autorizacao estar de pe.
 *
 * <p>O token "valido" carrega os dois scopes da cadeia -- {@code credito.leitura} para a API
 * deste servico e {@code carteira.leitura} para a troca seguinte. E o desenho de ADR-0015 posto
 * em teste: a segunda troca <b>reduz</b> capability em vez de introduzir scope novo.
 */
final class JwtTestSupport {

    static final KeyPair KEY_PAIR = generateKeyPair();

    private JwtTestSupport() {
    }

    static RSAPublicKey publicKey() {
        return (RSAPublicKey) KEY_PAIR.getPublic();
    }

    static String validToken(String subject, String audience, List<String> papeis) {
        return token(subject, audience, "credito.leitura carteira.leitura", papeis, Instant.now().plusSeconds(300));
    }

    static String tokenWithWrongAudience(String subject) {
        return token(subject, "outro-resource-server", "credito.leitura carteira.leitura",
                List.of("GERENTE_RELACIONAMENTO"), Instant.now().plusSeconds(300));
    }

    static String tokenWithoutCreditoScope(String subject, String audience) {
        return token(subject, audience, "carteira.leitura",
                List.of("GERENTE_RELACIONAMENTO"), Instant.now().plusSeconds(300));
    }

    /**
     * Least privilege por operacao (plano #0003, secao 9): o token delegado para a submissao
     * carrega {@code credito.escrita}, e NUNCA {@code credito.leitura} -- a troca de #0003 pede
     * so o que aquela operacao usa. Reaproveitado tanto para o caminho feliz do POST quanto para
     * provar que este MESMO token, sem {@code credito.leitura}, e recusado no GET (S6, scope
     * cruzado).
     */
    static String tokenComEscritaDeCredito(String subject, String audience) {
        return token(subject, audience, "credito.escrita carteira.leitura",
                List.of("GERENTE_RELACIONAMENTO"), Instant.now().plusSeconds(300));
    }

    static String tokenWithoutManagerRole(String subject, String audience) {
        return token(subject, audience, "credito.leitura carteira.leitura", List.of(), Instant.now().plusSeconds(300));
    }

    static String expiredToken(String subject, String audience) {
        return token(subject, audience, "credito.leitura carteira.leitura",
                List.of("GERENTE_RELACIONAMENTO"), Instant.now().minusSeconds(60));
    }

    /**
     * Token maquina-a-maquina (#0005, client_credentials do CoreLegado): sem {@code papeis} --
     * um token de client_credentials nunca carrega papel humano (CONTEXT-MAP.md).
     */
    static String machineToken(String subject, String audience, String scope) {
        return token(subject, audience, scope, List.of(), Instant.now().plusSeconds(300));
    }

    private static String token(String subject, String audience, String scope, List<String> papeis, Instant expiresAt) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(subject)
                    .audience(audience)
                    .issueTime(Date.from(Instant.now().minusSeconds(5)))
                    .expirationTime(Date.from(expiresAt))
                    .claim("scope", scope)
                    .claim("papeis", papeis)
                    .build();

            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).build(),
                    claims);
            jwt.sign(new RSASSASigner((RSAPrivateKey) KEY_PAIR.getPrivate()));
            return jwt.serialize();
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException("Falha ao assinar JWT de teste", e);
        }
    }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
