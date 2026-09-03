package com.fkmanager360.simuladorcorelegado.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Info e servers globais do contrato gerado (ADR-0019): as anotacoes nos controllers/DTOs de
 * {@code adapter/in/web} descrevem paths/schemas/operacoes; o que nao e por-operacao vive aqui,
 * uma unica vez. Sem security scheme -- este simulador nao tem Spring Security (ADR-0005: o
 * host-centric real que ele imita nao conhece OAuth2, e o unico consumidor e alcancado pela rede
 * interna do Compose).
 */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI simuladorCoreLegadoOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("fk-simulador-core-legado")
                        .version("0004")
                        .description("Sistema externo simulado (nao e um bounded context deste "
                                + "repositorio). Contrato host-centrico FICTICIO desta POC (ADR-0005) -- "
                                + "nao e replica de nenhum core bancario real. Quatro capacidades: consulta "
                                + "em lote dos dados mestres do Cliente e consulta das contas de um "
                                + "Cliente, ambas consumidas pela ACL de fk-servico-carteira-clientes; "
                                + "consulta dos dados de credito de uma conta e recepcao da instrucao de "
                                + "efetivacao, ambas consumidas pela ACL de fk-servico-credito. Cada "
                                + "contexto integra o legado pela sua propria ACL, apenas na fatia que lhe "
                                + "diz respeito (ADR-0004). fk-bff-gerente nunca chama este servico "
                                + "diretamente (AC30). O control plane de cenarios de efetivacao "
                                + "(ADR-0018), ativo apenas nos profiles local/demo/test, e deliberadamente "
                                + "separado deste contrato funcional e nao aparece aqui."))
                .servers(List.of(new Server()
                        .url("http://localhost:8090")
                        .description("Rede interna do Docker Compose (nome de servico), porta exposta localmente para dev.")));
    }
}
