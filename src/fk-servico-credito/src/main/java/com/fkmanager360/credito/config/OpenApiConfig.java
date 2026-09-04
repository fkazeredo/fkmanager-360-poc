package com.fkmanager360.credito.config;

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
    OpenAPI creditoOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("fk-servico-credito")
                        .version("0005")
                        .description("Resource Server do bounded context Credito, dono semantico do "
                                + "LimiteChequeEspecial (ADR-0004). Nao devolve dados cadastrais do "
                                + "Cliente (pertencem a CarteiraClientes) nem a ClassificacaoRiscoCreditoBase "
                                + "(insumo interno da politica). Toda resposta de erro carrega `codigo` "
                                + "estavel do dominio em ProblemDetail."))
                .servers(List.of(new Server()
                        .url("http://localhost:8083")
                        .description("Rede interna do Docker Compose, porta exposta localmente para dev.")))
                .components(new Components()
                        .addSecuritySchemes("bearerJwt", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Token emitido por fk-servidor-autorizacao (Token Exchange a "
                                        + "partir da sessao do fk-bff-gerente), aud=servico-credito. "
                                        + "Least privilege por operacao (credito.leitura/credito.escrita).")));
    }
}
