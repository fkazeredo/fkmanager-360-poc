package com.fkmanager360.credito.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * S8: a regra de dependencia desde o primeiro codigo (ADR-0020). Guardrail estrutural
 * independente -- nao prova qualidade do modelo de dominio, so a direcao da dependencia.
 */
@AnalyzeClasses(packages = "com.fkmanager360.credito", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule regra_de_dependencia_hexagonal = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("Domain").definedBy("..domain..")
            .layer("Application").definedBy("..application..")
            .layer("Adapter").definedBy("..adapter..")
            .whereLayer("Adapter").mayNotBeAccessedByAnyLayer()
            .whereLayer("Application").mayOnlyBeAccessedByLayers("Adapter")
            .whereLayer("Domain").mayOnlyBeAccessedByLayers("Application", "Adapter");

    @ArchTest
    static final ArchRule dominio_nao_depende_de_spring =
            noClasses().that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat().resideInAnyPackage("org.springframework..");

    @ArchTest
    static final ArchRule aplicacao_nao_depende_de_spring =
            noClasses().that().resideInAPackage("..application..")
                    .should().dependOnClassesThat().resideInAnyPackage("org.springframework..");

    @ArchTest
    static final ArchRule nenhuma_classe_deste_servico_usa_jpa_ou_hibernate =
            noClasses().should().dependOnClassesThat()
                    .resideInAnyPackage("jakarta.persistence..", "org.hibernate..");

    /**
     * ADR-0020 e S8 exigem ausencia de JPA, Kafka e Rabbit dentro do dominio -- a mesma frase,
     * tres tecnologias. Este ticket nao introduz nenhuma das tres, mas a guarda precisa existir
     * desde o primeiro codigo do modulo (o proprio criterio de S8), e nao so ser adicionada no
     * slice que introduzir mensageria (achado C1 do review de #0002: a regra estava presente para
     * JPA, ausente para Kafka/Rabbit, num modulo que ainda nao usa nenhum dos tres).
     */
    @ArchTest
    static final ArchRule dominio_nao_depende_de_kafka_ou_rabbit =
            noClasses().that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.springframework.kafka..", "org.apache.kafka..",
                            "org.springframework.amqp..", "com.rabbitmq..");

    /**
     * Enquanto nao houver estado duravel de Credito (ADR-0010, ADR-0014), nenhuma classe deste
     * servico referencia JDBC, DataSource ou Flyway. O que esta regra prova, precisamente: nenhum
     * <b>bytecode</b> deste modulo importa esses tipos. O que ela NAO prova, sozinha: que
     * persistencia nao possa ser introduzida por outro caminho -- um starter Spring Boot com
     * autoconfiguracao por classpath, ou um recurso de migration adicionado sem nenhuma classe
     * Java nova referenciando-o, nao deixariam rastro aqui (achado C2 do review de #0002). O
     * teste companheiro abaixo fecha essa lacuna, checando o RECURSO, nao a referencia de classe.
     */
    @ArchTest
    static final ArchRule enquanto_nao_ha_estado_duravel_nenhuma_classe_referencia_jdbc_ou_flyway =
            noClasses().should().dependOnClassesThat()
                    .resideInAnyPackage("java.sql..", "javax.sql..", "org.springframework.jdbc..", "org.flywaydb..");

    /**
     * Companheiro da regra acima: prova ausencia do proprio recurso de migration no classpath, e
     * nao apenas ausencia de referencia de classe a ele. Adicionar Flyway + migrations SQL sem
     * nenhuma classe Java nova (autoconfiguracao do Spring Boot faz exatamente isso a partir de
     * {@code spring.datasource.*} e de arquivos em {@code db/migration}) passaria pela regra
     * ArchUnit acima incolume; este teste e o que de fato o pegaria.
     */
    @Test
    void enquantoNaoHaEstadoDuravel_nenhumaMigrationEstaNoClasspath() {
        assertThat(getClass().getClassLoader().getResource("db/migration")).isNull();
    }

    /**
     * ADR-0011: entidades de dominio Java nunca atravessam bounded contexts. A separacao em
     * modulos Maven distintos ja garante isso; esta regra torna a intencao visivel dentro do
     * modulo, e falha cedo se alguem adicionar a dependencia "so para reaproveitar um record".
     */
    @ArchTest
    static final ArchRule nenhuma_dependencia_java_direta_de_outro_bounded_context =
            noClasses().should().dependOnClassesThat()
                    .resideInAnyPackage("com.fkmanager360.carteiraclientes..", "com.fkmanager360.simuladorcorelegado..");

    /**
     * A ACL e a unica fronteira que pode conhecer o vocabulario host-centric (ADR-0005): nenhum
     * COD-RET, campo abreviado ou formato do host atravessa para dentro.
     */
    @ArchTest
    static final ArchRule dominio_e_aplicacao_nao_conhecem_o_vocabulario_do_host =
            noClasses().that().resideInAnyPackage("..domain..", "..application..")
                    .should().dependOnClassesThat().resideInAPackage("..adapter.out.legacy..");
}
