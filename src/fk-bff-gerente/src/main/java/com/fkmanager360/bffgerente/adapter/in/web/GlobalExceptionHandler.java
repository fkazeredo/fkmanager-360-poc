package com.fkmanager360.bffgerente.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Set;

/**
 * A taxonomia de erro completa desta fronteira (achado I1 do review de #0002; estendida pelo
 * plano #0003, secao 9 "bff-gerente"). Origens distintas, cada uma com o status/`codigo` que lhe
 * cabe -- nenhuma pode escapar para um 500 generico:
 *
 * <ul>
 *   <li><b>entrada invalida do proprio BFF</b> -- path variable fora do formato, ou
 *       {@code Idempotency-Key} ausente na propria borda do BFF -- recusada antes de qualquer
 *       chamada remota;</li>
 *   <li><b>401 de um Resource Server</b> -- o token <i>delegado</i> foi recusado, nao a sessao do
 *       browser -- NAO vira 401 (isso confundiria "usuario precisa logar de novo" com "a cadeia
 *       de Token Exchange quebrou"); vira 502, taxonomia de integracao. Continua tratado ANTES do
 *       handler generico abaixo, por especificidade de tipo do Spring
 *       ({@code HttpClientErrorException.Unauthorized} e subtipo de
 *       {@code HttpClientErrorException}) -- este comportamento NAO MUDA;</li>
 *   <li><b>403/404 de um Resource Server</b> -- resposta de negocio do backend dono do recurso
 *       (ADR-0007), SEMPRE atravessa com o MESMO status, com ou sem {@code codigo} no corpo
 *       upstream. Isto e deliberado e NAO regride para 502 quando o {@code codigo} esta ausente:
 *       {@code fk-servico-carteira-clientes} (AC23, provado desde #0002) nunca adotou
 *       {@code codigo} e nao e tocado por #0003, e continuar a exigir {@code codigo} para 403/404
 *       quebraria a garantia ja provada de que "sem direito de atendimento atual, a consulta
 *       responde 403" -- 403 e 404 sempre significaram a mesma coisa autoritativa neste sistema,
 *       vindos de QUALQUER Resource Server, independentemente de carregarem {@code codigo}.
 *       Quando o corpo upstream carrega um {@code codigo} reconhecido (caso de
 *       {@code fk-servico-credito}, a partir de #0003), ele e preservado no envelope; quando nao
 *       (caso de {@code fk-servico-carteira-clientes}), o envelope sai so com {@code status};</li>
 *   <li><b>demais 4xx novos na taxonomia de encaminhamento -- 400/409/422, introduzidos por
 *       #0003</b> -- estes NUNCA existiram no encaminhamento do BFF antes deste ticket e so podem
 *       vir de {@code fk-servico-credito}, que sempre publica {@code codigo}: um handler
 *       orientado por allow-list le o status e tenta desserializar o corpo como
 *       {@link ProblemDetailUpstream}. Se a desserializacao falhar OU o {@code codigo} nao estiver
 *       na allow-list publicada para aquele status -&gt; 502 {@code DEPENDENCIA_INDISPONIVEL}
 *       (nunca um erro repassado cegamente -- guardrail explicito do ticket: so combinacoes
 *       {@code status}+{@code codigo} publicadas no contrato preservam a semantica downstream).
 *       Se bater -&gt; o MESMO status HTTP, com o mesmo envelope publico;</li>
 *   <li><b>qualquer outro codigo de status HTTP inesperado</b> -- (ex.: 405, 429) -- cai no mesmo
 *       handler generico acima e, por nao estar em nenhuma allow-list, vira 502;</li>
 *   <li><b>corpo 2xx incompleto, ou qualquer 5xx/timeout/reset</b> -- falha de integracao, vira a
 *       mensagem unica de indisponibilidade; o gerente nao precisa saber qual dependencia caiu, e
 *       a distincao permanece em protocolo, metrica e diagnostico.</li>
 * </ul>
 *
 * <p>Em todos os casos, {@code detail}/{@code instance}/{@code type} e qualquer propriedade que o
 * upstream venha a acrescentar NUNCA atravessam para o browser -- nenhuma propriedade nao
 * publicada no OpenAPI do BFF sai daqui, e mensagens internas de diagnostico ficam no log do BFF,
 * nunca na resposta.
 *
 * <p><b>Por que um envelope proprio, e nao {@code org.springframework.http.ProblemDetail}</b>: o
 * Spring MVC preenche automaticamente a propriedade {@code instance} de um {@code ProblemDetail}
 * retornado por um {@code @ExceptionHandler} com o path da PROPRIA requisicao, quando ela esta
 * nula ({@code HttpEntityMethodProcessor}/{@code ExceptionHandlerExceptionResolver}) -- o que
 * vazaria justamente o que este handler existe para nunca vazar. Um {@link EnvelopeErroPublico}
 * simples, totalmente controlado por esta classe, evita esse comportamento de framework.
 */
@RestControllerAdvice(basePackages = "com.fkmanager360.bffgerente.adapter.in.web")
class GlobalExceptionHandler {

    /**
     * Allow-list de {@code (status, codigo)} publicados no OpenAPI do BFF para os status NOVOS na
     * taxonomia de encaminhamento -- 400/409/422, introduzidos por #0003 e que so podem vir de
     * {@code fk-servico-credito} (plano #0003, secoes 3 e 9). 403/404 NAO entram aqui
     * deliberadamente: ver {@link #semDireitoDeAtendimento} e {@link #naoEncontrado}. Uma
     * combinacao fora desta lista e tratada exatamente como um corpo ilegivel: 502
     * {@code DEPENDENCIA_INDISPONIVEL}.
     *
     * <p>Chave {@code UNPROCESSABLE_CONTENT}, e nao {@code UNPROCESSABLE_ENTITY}: nesta versao do
     * Spring Framework os dois nomes coexistem como constantes DISTINTAS do enum {@code
     * HttpStatus} (o padrao HTTP renomeou "422 Unprocessable Entity" para "422 Unprocessable
     * Content"), e {@code HttpStatus.resolve(422)} devolve especificamente {@code
     * UNPROCESSABLE_CONTENT} -- usar a constante {@code UNPROCESSABLE_ENTITY} aqui faz o lookup no
     * mapa falhar silenciosamente (nunca lanca excecao; so nunca encontra o status), e todo 422
     * vira 502 (bug real encontrado e corrigido durante o desenvolvimento deste ticket).
     */
    private static final Map<HttpStatus, Set<String>> CODIGOS_PERMITIDOS_POR_STATUS = Map.of(
            HttpStatus.BAD_REQUEST, Set.of(
                    "IDEMPOTENCY_KEY_AUSENTE", "IDEMPOTENCY_KEY_INVALIDA", "COMANDO_ILEGIVEL", "IDENTIFICADOR_INVALIDO"),
            HttpStatus.CONFLICT, Set.of(
                    "LIMITE_VIGENTE_DESATUALIZADO", "SOLICITACAO_NAO_TERMINAL_EXISTENTE", "IDEMPOTENCIA_EM_PROCESSAMENTO"),
            HttpStatus.UNPROCESSABLE_CONTENT, Set.of(
                    "COMANDO_INVALIDO", "LIMITE_SOLICITADO_NAO_AUMENTA", "IDEMPOTENCIA_FINGERPRINT_DIVERGENTE"));

    private final ObjectMapper objectMapper;

    GlobalExceptionHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<EnvelopeErroPublico> entradaInvalida(IllegalArgumentException e) {
        return envelope(HttpStatus.BAD_REQUEST, "IDENTIFICADOR_INVALIDO");
    }

    /**
     * {@code Idempotency-Key} ausente na propria requisicao ao BFF: como o header e obrigatorio
     * no metodo do controller, o Spring recusa ANTES de qualquer chamada a servico-credito --
     * este handler garante que o gerente ainda recebe o mesmo {@code codigo} que receberia se a
     * requisicao tivesse alcancado Credito.
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<EnvelopeErroPublico> idempotencyKeyAusenteNoBff(MissingRequestHeaderException e) {
        return envelope(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_AUSENTE");
    }

    /**
     * Um Resource Server recusando o token DELEGADO (401) nao pode virar 401 para o browser (ver
     * Javadoc da classe) -- este comportamento nao muda. Continua tratado ANTES do handler
     * generico abaixo por especificidade de tipo do Spring.
     */
    @ExceptionHandler(HttpClientErrorException.Unauthorized.class)
    ResponseEntity<EnvelopeErroPublico> tokenDelegadoRecusado(HttpClientErrorException.Unauthorized e) {
        return envelope(HttpStatus.BAD_GATEWAY, "DEPENDENCIA_INDISPONIVEL");
    }

    /**
     * 403 de um Resource Server: resposta de negocio do backend dono do recurso (ADR-0007).
     * SEMPRE atravessa como 403 -- com {@code codigo} quando o upstream o publica
     * ({@code fk-servico-credito}, #0003), sem quando nao ({@code fk-servico-carteira-clientes},
     * AC23 desde #0002). Nunca cai no handler generico abaixo, que exige {@code codigo} conhecido
     * -- essa exigencia so vale para status novos na taxonomia de encaminhamento (ver Javadoc da
     * classe).
     */
    @ExceptionHandler(HttpClientErrorException.Forbidden.class)
    ResponseEntity<EnvelopeErroPublico> semDireitoDeAtendimento(HttpClientErrorException.Forbidden e) {
        return envelope(HttpStatus.FORBIDDEN, codigoUpstreamOuNulo(e));
    }

    /** Ver Javadoc de {@link #semDireitoDeAtendimento} -- mesma regra, para 404. */
    @ExceptionHandler(HttpClientErrorException.NotFound.class)
    ResponseEntity<EnvelopeErroPublico> naoEncontrado(HttpClientErrorException.NotFound e) {
        return envelope(HttpStatus.NOT_FOUND, codigoUpstreamOuNulo(e));
    }

    /**
     * Handler para os status NOVOS na taxonomia de encaminhamento -- 400/409/422, introduzidos por
     * #0003 -- e qualquer outro 4xx inesperado (405, 429, ...). Ao contrario de 403/404
     * ({@link #semDireitoDeAtendimento}/{@link #naoEncontrado}), estes SO podem vir de
     * {@code fk-servico-credito}, que sempre publica {@code codigo}: um {@code codigo} ausente ou
     * desconhecido aqui e tratado como corpo ilegivel, nunca repassado cegamente.
     */
    @ExceptionHandler(HttpClientErrorException.class)
    ResponseEntity<EnvelopeErroPublico> respostaDeNegocioNovaNaTaxonomia(HttpClientErrorException e) {
        HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
        String codigo = codigoUpstreamOuNulo(e);

        if (status == null || codigo == null || !codigosPermitidosPara(status).contains(codigo)) {
            return envelope(HttpStatus.BAD_GATEWAY, "DEPENDENCIA_INDISPONIVEL");
        }
        return envelope(status, codigo);
    }

    /**
     * Um corpo {@code 2xx} incompleto -- campo obrigatorio ausente ou nulo -- que a composicao de
     * {@link AtendimentoResponse} detecta: falha de contrato, nunca dado incompleto seguindo em
     * frente como se fosse valido.
     */
    @ExceptionHandler(DependenciaRespostaInvalidaException.class)
    ResponseEntity<EnvelopeErroPublico> corpoUpstreamIncompleto(DependenciaRespostaInvalidaException e) {
        return envelope(HttpStatus.BAD_GATEWAY, "DEPENDENCIA_INDISPONIVEL");
    }

    @ExceptionHandler({HttpServerErrorException.class, ResourceAccessException.class})
    ResponseEntity<EnvelopeErroPublico> dependenciaIndisponivel(Exception e) {
        return envelope(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCIA_INDISPONIVEL");
    }

    /**
     * Le SOMENTE {@code codigo} do corpo upstream -- nunca {@code detail}. {@code null} sempre que
     * o corpo nao existir, nao for JSON, ou nao carregar a propriedade.
     */
    private String codigoUpstreamOuNulo(HttpClientErrorException e) {
        try {
            ProblemDetailUpstream upstream =
                    objectMapper.readValue(e.getResponseBodyAsByteArray(), ProblemDetailUpstream.class);
            return upstream == null ? null : upstream.codigo();
        } catch (Exception naoDesserializou) {
            return null;
        }
    }

    private static Set<String> codigosPermitidosPara(HttpStatus status) {
        return CODIGOS_PERMITIDOS_POR_STATUS.getOrDefault(status, Set.of());
    }

    private static ResponseEntity<EnvelopeErroPublico> envelope(HttpStatus status, String codigo) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(new EnvelopeErroPublico(status.value(), codigo));
    }

    /**
     * Leitura MINIMA do corpo upstream -- so {@code codigo}. Jackson ignora {@code detail},
     * {@code instance}, {@code type} e qualquer outra propriedade por padrao (sem
     * {@code FAIL_ON_UNKNOWN_PROPERTIES}): essas propriedades nunca chegam a ser lidas por esta
     * classe, e portanto nunca podem vazar para o browser.
     */
    private record ProblemDetailUpstream(String codigo) {
    }

    /**
     * O envelope publico do BFF: {@code status} + {@code codigo}, e nada mais -- nenhum campo de
     * detalhe alem destes esta publicado no OpenAPI deste ticket. Nunca ecoa o texto livre que
     * veio de um Resource Server; mensagens internas de diagnostico ficam no log do BFF.
     *
     * <p>{@code codigo} e omitido (nao {@code null} explicito) quando o upstream nao publica um --
     * caso de {@code fk-servico-carteira-clientes} em 403/404 (ver Javadoc da classe): ausencia de
     * codigo e informacao legitima, nao um valor a fingir que existe.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record EnvelopeErroPublico(int status, String codigo) {
    }
}
