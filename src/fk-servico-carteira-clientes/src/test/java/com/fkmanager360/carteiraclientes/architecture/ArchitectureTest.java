package com.fkmanager360.carteiraclientes.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMembers;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

/**
 * S8: a regra de dependencia desde o primeiro codigo (ADR-0020). Guardrail estrutural
 * independente -- nao prova qualidade do modelo de dominio, so a direcao da dependencia.
 */
@AnalyzeClasses(packages = "com.fkmanager360.carteiraclientes", importOptions = ImportOption.DoNotIncludeTests.class)
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

    /**
     * Falsifica: uma classe de dominio ou aplicacao importando JPA/Hibernate/Spring Data --
     * persistencia e detalhe de adapter (ADR-0020). A adocao de JPA (Owner, 2026) move a
     * cerimonia de {@code JdbcClient} para {@code adapter/out/persistence/**}, nunca para dentro
     * do hexagono.
     */
    @ArchTest
    static final ArchRule dominio_e_aplicacao_nao_dependem_de_persistencia =
            noClasses().that().resideInAnyPackage("..domain..", "..application..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "jakarta.persistence..", "org.hibernate..", "org.springframework.data..");

    /**
     * Falsifica: JPA/Hibernate/Spring Data aparecendo fora de {@code adapter.out.persistence} --
     * prova que a cerimonia de persistencia fica contida num unico pacote, e nao vaza para
     * controllers, ACLs ou config.
     */
    @ArchTest
    static final ArchRule jpa_somente_na_persistencia =
            noClasses().that().resideOutsideOfPackage("..adapter.out.persistence..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "jakarta.persistence..", "org.hibernate..", "org.springframework.data..");

    /**
     * Falsifica: um controller acessando {@code Repository}/entity diretamente, pulando a
     * aplicacao e a port de saida -- o adapter de entrada so pode falar com a aplicacao
     * (ADR-0020).
     */
    @ArchTest
    static final ArchRule web_nao_acessa_repository =
            noClasses().that().resideInAPackage("..adapter.in.web..")
                    .should().dependOnClassesThat().resideInAPackage("..adapter.out.persistence..");

    /**
     * Falsifica: um membro gerado por Lombok (marcado com {@code @lombok.Generated}, retencao
     * CLASS -- {@code lombok.config} na raiz liga {@code addLombokGeneratedAnnotation}) declarado
     * numa classe de dominio ou aplicacao. Provado empiricamente falsificavel: anotar
     * temporariamente {@code ConfirmarDireitoDeAtendimento} com {@code @RequiredArgsConstructor} e
     * rodar este teste produz vermelho (ArchUnit le a anotacao CLASS-retention pelo bytecode via
     * ASM, sem depender de reflection em runtime, que so enxergaria RUNTIME-retention).
     */
    @ArchTest
    static final ArchRule hexagono_nao_usa_lombok =
            noMembers().that().areDeclaredInClassesThat().resideInAnyPackage("..domain..", "..application..")
                    .should().beAnnotatedWith("lombok.Generated");
}
