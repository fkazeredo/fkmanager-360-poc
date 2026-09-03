package com.fkmanager360.bffgerente.adapter.in.web;

import com.fkmanager360.bffgerente.config.DelegatedTokenResolver;
import com.fkmanager360.bffgerente.config.TokenExchangeConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.util.regex.Pattern;

/**
 * Proxy autenticado da submissao (plano #0003, secao 9 "bff-gerente"). Encaminhamento puro, sem
 * composicao: o corpo e o header {@code Idempotency-Key} atravessam INTACTOS para servico-credito
 * -- este controller nao gera, nao regenera e nao reinterpreta nenhum dos dois (spec, secao
 * "Idempotencia da submissao"). Por isso o corpo trafega como {@code String} cru, nunca
 * desserializado aqui: o BFF nao precisa (nem deve) conhecer a forma do comando para repassa-lo.
 *
 * <p>Usa {@link TokenExchangeConfig#REGISTRATION_CREDITO_ESCRITA} -- registration distinta da que
 * {@link AtendimentoController} usa para o GET do limite vigente ({@code REGISTRATION_CREDITO_LEITURA}):
 * least privilege por operacao, o token delegado desta chamada carrega {@code credito.escrita},
 * nunca {@code credito.leitura}.
 *
 * <p>O status HTTP devolvido por Credito ({@code 201} criacao, {@code 200} replay) e propagado tal
 * como veio; o corpo de sucesso tambem e cru. Erros (4xx/5xx) sao tratados por
 * {@link GlobalExceptionHandler}, que produz o envelope publico proprio do BFF -- nunca repassa o
 * {@code ProblemDetail} de Credito verbatim.
 */
@RestController
@RequiredArgsConstructor
public class SolicitacaoAumentoLimiteProxyController {

    private static final Pattern IDENTIFICADOR_HOST = Pattern.compile("[0-9]{1,10}");

    private final RestClient creditoRestClient;
    private final DelegatedTokenResolver tokenResolver;

    @PostMapping(path = "/api/clientes/{clienteId}/contas/{contaId}/solicitacoes-aumento-limite", produces = "application/json")
    ResponseEntity<String> submeter(
            @PathVariable String clienteId, @PathVariable String contaId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody String corpoCru,
            Authentication authentication, HttpServletRequest request, HttpServletResponse response) {

        validarIdentificador(clienteId, "clienteId");
        validarIdentificador(contaId, "contaId");

        String tokenDelegado = tokenResolver.tokenPara(
                TokenExchangeConfig.REGISTRATION_CREDITO_ESCRITA, authentication, request, response);

        ResponseEntity<String> upstream = creditoRestClient.post()
                .uri("/clientes/{clienteId}/contas/{contaId}/solicitacoes-aumento-limite", clienteId, contaId)
                .header("Authorization", "Bearer " + tokenDelegado)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(corpoCru)
                .retrieve()
                .toEntity(String.class);

        // So status + corpo cru atravessam (Javadoc da classe) -- NUNCA o HeaderMap inteiro de
        // upstream. `.toEntity(...)` inclui headers hop-by-hop de servico-credito (em particular
        // `Transfer-Encoding: chunked`, quando a resposta upstream usa chunked); devolve-los aqui
        // fazia o container adicionar o SEU PROPRIO `Transfer-Encoding: chunked` por cima,
        // resultando numa resposta HTTP com o header duplicado -- nginx recusa isso com `502
        // upstream sent duplicate header line` (bug real, encontrado pelo Playwright contra a
        // stack real, corrigido nesta mesma etapa). Content-Length/Transfer-Encoding da resposta
        // final ficam a cargo do proprio container, a partir do corpo que de fato escrevemos.
        return ResponseEntity.status(upstream.getStatusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(upstream.getBody());
    }

    /**
     * Mesmo padrao/regex ja usado em {@link AtendimentoController#validarIdentificador} (privado
     * la, replicado aqui): um identificador fora do formato host nunca deveria virar uma chamada
     * remota que so vai falhar la na frente.
     */
    private static void validarIdentificador(String valor, String nomeDoCampo) {
        if (valor == null || !IDENTIFICADOR_HOST.matcher(valor).matches()) {
            throw new IllegalArgumentException(nomeDoCampo + " deve ser numerico, com ate 10 digitos: " + valor);
        }
    }
}
