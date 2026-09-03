package com.fkmanager360.carteiraclientes.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Info, servers e o security scheme globais do contrato gerado (ADR-0019): as anotacoes nos
 * controllers/DTOs de {@code adapter/in/web} descrevem paths/schemas/operacoes; o que nao e
 * por-operacao vive aqui, uma unica vez.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI carteiraClientesOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("fk-servico-carteira-clientes")
                        .version("0002")
                        .description("Resource Server do bounded context CarteiraClientes: autoridade sobre "
                                + "a associacao atual GerenteRelacionamento-Cliente. Expoe a listagem paginada "
                                + "da carteira do gerente autenticado, as ContaCorrentes de um Cliente e o "
                                + "contexto de atendimento de uma conta -- tudo composto com dados lidos de "
                                + "fk-simulador-core-legado pela ACL propria deste contexto (ADR-0004). Nao e "
                                + "fachada financeira: nao conhece LimiteChequeEspecial (ADR-0004, AC30). Em "
                                + "toda consulta por Cliente, o direito de atendimento ATUAL e verificado na "
                                + "persistencia local ANTES de qualquer chamada ao CoreLegado (AC23)."))
                .servers(List.of(new Server()
                        .url("http://localhost:8081")
                        .description("Rede interna do Docker Compose (nome de servico), porta exposta localmente para dev.")))
                .components(new Components()
                        .addSecuritySchemes("bearerJwt", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Token emitido por fk-servidor-autorizacao (Token Exchange a "
                                        + "partir da sessao do fk-bff-gerente), com aud=servico-carteira-clientes "
                                        + "e scope carteira.leitura.")));
    }
}
