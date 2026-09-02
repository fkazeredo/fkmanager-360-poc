package com.fkmanager360.credito.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

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
     * Este ticket materializa Credito <b>sem</b> persistencia (ADR-0010, ADR-0014). Enquanto nao
     * houver estado duravel, nenhuma classe deste servico toca JDBC, DataSource ou Flyway -- e o
     * dia em que credito_db nascer, esta regra cai junto com a spec que a justificar, e nao por
     * descuido.
     */
    @ArchTest
    static final ArchRule enquanto_nao_ha_estado_duravel_nao_ha_persistencia =
            noClasses().should().dependOnClassesThat()
                    .resideInAnyPackage("java.sql..", "javax.sql..", "org.springframework.jdbc..", "org.flywaydb..");

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
