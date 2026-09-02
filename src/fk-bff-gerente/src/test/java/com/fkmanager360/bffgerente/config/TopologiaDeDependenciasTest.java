package com.fkmanager360.bffgerente.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A prova estrutural de AC30 -- "o BFF nao fala com o simulador-core-legado" -- substituindo o
 * teste vacuo que este ticket tinha antes (achado I7 do review de #0002): um {@code WireMockServer}
 * cujo endereco nenhum componente do BFF conhecia, e cujo {@code verify(0, ...)} era portanto
 * verdadeiro por construcao, nao por falsificacao de coisa alguma.
 *
 * <p>Duas evidencias independentes e genuinamente falsificaveis, cada uma cobrindo um angulo que
 * o outro nao cobre:
 *
 * <ol>
 *   <li><b>Ausencia de classe.</b> {@code fk-bff-gerente} nao declara {@code fk-simulador-core-legado}
 *       como dependencia Maven -- {@code pom.xml} nao o lista --, e o classloader deste processo
 *       simplesmente nao consegue carregar nenhuma classe daquele modulo. Isto prova ausencia de
 *       <i>adapter</i> possivel: nao ha como este processo falar HTTP com o simulador usando um
 *       vocabulario que ele nem consegue instanciar.</li>
 *   <li><b>Configuracao de clients.</b> O unico conjunto de beans {@link RestClient} configurados
 *       no contexto Spring e exatamente os dois destinos autorizados da experiencia --
 *       {@code carteiraClientesRestClient} e {@code creditoRestClient}. Se algum dia um terceiro
 *       {@code RestClient} for adicionado apontando para o Core, este teste falha imediatamente,
 *       no boot do contexto, nomeando o bean extra.</li>
 * </ol>
 *
 * <p>Nenhuma das duas e "prove que o codigo Java nunca fara uma chamada HTTP crua com
 * {@code java.net.http.HttpClient}" -- essa e uma afirmacao mais forte do que o par
 * classe-ausente + clients-configurados consegue demonstrar sozinho, e por isso a combinacao
 * fica registrada aqui explicitamente, em vez de um unico teste alegar mais do que prova.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration,"
                        + "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration",
                "spring.security.oauth2.client.provider.servidor-autorizacao.authorization-uri=http://servidor-autorizacao.invalid/oauth2/authorize",
                "spring.security.oauth2.client.provider.servidor-autorizacao.token-uri=http://servidor-autorizacao.invalid/oauth2/token",
                "spring.security.oauth2.client.provider.servidor-autorizacao.jwk-set-uri=http://servidor-autorizacao.invalid/oauth2/jwks",
                "spring.security.oauth2.client.provider.servidor-autorizacao.user-info-uri=http://servidor-autorizacao.invalid/userinfo",
                "spring.security.oauth2.client.provider.servidor-autorizacao.user-name-attribute=sub",
                "bff-gerente.carteira-clientes.base-url=http://carteira-clientes.invalid",
                "bff-gerente.credito.base-url=http://credito.invalid"
        })
class TopologiaDeDependenciasTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void oUnicoConjuntoDeRestClientsConfiguradosSaoOsDoisDestinosAutorizados() {
        Map<String, RestClient> restClients = context.getBeansOfType(RestClient.class);

        assertThat(restClients.keySet())
                .as("qualquer RestClient alem destes dois seria uma nova integracao HTTP que este "
                        + "teste precisa nomear e falhar, nao deixar passar em silencio")
                .containsExactlyInAnyOrder("carteiraClientesRestClient", "creditoRestClient");
    }

    @Test
    void nenhumaClasseDoSimuladorCoreLegadoEstaNoClasspathDesteProcesso() {
        // fk-bff-gerente nao declara fk-simulador-core-legado como dependencia Maven -- este
        // teste prova a consequencia disso em tempo de execucao: o classloader deste processo
        // nao consegue instanciar nenhum tipo daquele modulo, entao nao ha como este codigo
        // conhecer o vocabulario host-centric do simulador, muito menos falar HTTP com ele.
        assertThatThrownBy(() -> Class.forName("com.fkmanager360.simuladorcorelegado.SimuladorCoreLegadoApplication"))
                .isInstanceOf(ClassNotFoundException.class);
    }
}
