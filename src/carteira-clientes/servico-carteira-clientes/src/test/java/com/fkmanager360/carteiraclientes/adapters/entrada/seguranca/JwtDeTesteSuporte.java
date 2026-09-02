package com.fkmanager360.carteiraclientes.adapters.entrada.seguranca;

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
import java.text.ParseException;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * JWT controlado (ADR-0018): assina tokens de teste com uma chave RSA gerada em memoria, sem
 * depender de servidor-autorizacao estar de pe -- e assim que S6 prova audience, scope e papel.
 */
final class JwtDeTesteSuporte {

    static final KeyPair PAR_DE_CHAVES = gerarParDeChaves();

    private JwtDeTesteSuporte() {
    }

    static RSAPublicKey chavePublica() {
        return (RSAPublicKey) PAR_DE_CHAVES.getPublic();
    }

    static String tokenValido(String subject, String audience, List<String> papeis) {
        return token(subject, audience, "carteira.leitura", papeis, Instant.now().plusSeconds(300));
    }

    static String tokenComAudienceErrada(String subject) {
        return token(subject, "outro-resource-server", "carteira.leitura", List.of("GERENTE_RELACIONAMENTO"), Instant.now().plusSeconds(300));
    }

    static String tokenSemScopeDeCarteira(String subject, String audience) {
        return token(subject, audience, "outra.capacidade", List.of("GERENTE_RELACIONAMENTO"), Instant.now().plusSeconds(300));
    }

    static String tokenSemPapelDeGerente(String subject, String audience) {
        return token(subject, audience, "carteira.leitura", List.of(), Instant.now().plusSeconds(300));
    }

    static String tokenExpirado(String subject, String audience) {
        return token(subject, audience, "carteira.leitura", List.of("GERENTE_RELACIONAMENTO"), Instant.now().minusSeconds(60));
    }

    private static String token(String subject, String audience, String scope, List<String> papeis, Instant expiraEm) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(subject)
                    .audience(audience)
                    .issueTime(Date.from(Instant.now().minusSeconds(5)))
                    .expirationTime(Date.from(expiraEm))
                    .claim("scope", scope)
                    .claim("papeis", papeis)
                    .build();

            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).build(),
                    claims);
            jwt.sign(new RSASSASigner((RSAPrivateKey) PAR_DE_CHAVES.getPrivate()));
            return jwt.serialize();
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException("Falha ao assinar JWT de teste", e);
        }
    }

    private static KeyPair gerarParDeChaves() {
        try {
            KeyPairGenerator gerador = KeyPairGenerator.getInstance("RSA");
            gerador.initialize(2048);
            return gerador.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
