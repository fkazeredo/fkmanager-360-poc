package com.fkmanager360.bffgerente.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Info, servers e o security scheme globais do contrato gerado (ADR-0019): as anotacoes nos
 * controllers/DTOs de {@code adapter/in/web} descrevem paths/schemas/operacoes; o que nao e
 * por-operacao vive aqui, uma unica vez.
 *
 * <p>Diferenca em relacao a fk-servico-credito: este servico autentica por cookie de sessao
 * (OAuth2 Login + Spring Session/Redis), nao por bearer JWT -- o security scheme abaixo e
 * {@code apiKey}/{@code cookie}, espelhando o mesmo nome e semantica ja documentados no contrato
 * anterior deste servico.
 *
 * <p>{@code /logout} nao tem metodo de controller para anotar -- e configurado declarativamente
 * em {@link SecurityConfig} (via {@code .logout(...)}), com um {@code LogoutSuccessHandler} que
 * escreve a resposta a mao (RP-Initiated Logout, OIDC). Documentado aqui via
 * {@link OpenApiCustomizer}, o mecanismo do springdoc para acrescentar operacoes que nao vem de
 * reflexao sobre um {@code @RestController}. O corpo/status refletem o comportamento REAL do
 * handler (200 com {@code redirectUrl}), nao o 204 do contrato hand-authored anterior -- que
 * ficou desatualizado quando o logout ganhou o fluxo RP-Initiated.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI bffGerenteOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("fk-bff-gerente")
                        .version("0003")
                        .description("Backend-for-frontend do GerenteRelacionamento. Fronteira web -- nao e "
                                + "bounded context (CONTEXT-MAP.md). Conduz Authorization Code + PKCE + OIDC "
                                + "contra fk-servidor-autorizacao, mantem a sessao em Redis via Spring Session "
                                + "e expoe ao app-gerente somente o que o Angular precisa: identidade da "
                                + "sessao corrente, a carteira, e a composicao da tela de atendimento a partir "
                                + "de fk-servico-carteira-clientes e fk-servico-credito (AC30). Nenhum access "
                                + "token ou refresh token atravessa esta fronteira para o browser (AC19) -- o "
                                + "BFF obtem por Token Exchange um token para cada destino e nunca reencaminha "
                                + "o token de login (ADR-0015). Nao implementa regra de credito, nao fala com "
                                + "fk-simulador-core-legado e nao substitui a autorizacao de recurso feita "
                                + "pelos servicos. Produz o seu PROPRIO envelope publico de erro -- nunca "
                                + "repassa o ProblemDetail upstream verbatim."))
                .servers(List.of(new Server()
                        .url("/bff")
                        .description("Origem publica atras do nginx (docker compose), TLS local.")))
                .components(new Components()
                        .addSecuritySchemes("cookieSessao", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.COOKIE)
                                .name("SESSION")
                                .description("Cookie de sessao opaco (Spring Session/Redis), HttpOnly, Secure, "
                                        + "SameSite=Lax. Nunca carrega access token ou refresh token -- eles "
                                        + "ficam no OAuth2AuthorizedClientRepository, server-side.")));
    }

    @Bean
    OpenApiCustomizer logoutPathCustomizer() {
        return openApi -> {
            if (openApi.getPaths() == null) {
                openApi.setPaths(new Paths());
            }
            openApi.getPaths().addPathItem("/logout", new PathItem().post(logoutOperation()));
        };
    }

    private static Operation logoutOperation() {
        ObjectSchema corpoRedirect = new ObjectSchema();
        corpoRedirect.setDescription("Escrito a mao pelo LogoutSuccessHandler (SecurityConfig) -- nao ha DTO "
                + "Java correspondente.");
        corpoRedirect.addProperty("redirectUrl", new StringSchema()
                .description("Destino de navegacao do browser apos o logout local: o end-session-endpoint do "
                        + "servidor-autorizacao (RP-Initiated Logout, com id_token_hint) quando havia sessao "
                        + "OIDC, ou a origem publica do app-gerente quando nao havia.")
                .example("https://localhost:4200/connect/logout?id_token_hint=...&post_logout_redirect_uri=..."));

        return new Operation()
                .operationId("encerrarSessao")
                .addTagsItem("sessao")
                .summary("Encerra a sessao local e devolve o destino de navegacao (RP-Initiated Logout)")
                .description("Endpoint do Spring Security (.logout(...) em SecurityConfig) -- sem metodo de "
                        + "controller correspondente para anotar. Escrita real, sujeita a CSRF (AC20): exige "
                        + "o token do cookie XSRF-TOKEN ecoado no header X-XSRF-TOKEN.")
                .security(List.of(new SecurityRequirement().addList("cookieSessao")))
                .responses(new ApiResponses()
                        .addApiResponse("200", new ApiResponse()
                                .description("Sessao local encerrada; cookie de sessao invalidado. Corpo "
                                        + "carrega o destino de navegacao RP-Initiated Logout.")
                                .content(new Content().addMediaType("application/json",
                                        new MediaType().schema(corpoRedirect))))
                        .addApiResponse("403", new ApiResponse()
                                .description("Token CSRF ausente ou invalido.")));
    }
}
