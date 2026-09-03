package com.fkmanager360.credito.architecture;

import com.fkmanager360.credito.domain.MotorDecisaoCredito;
import com.fkmanager360.credito.domain.PoliticaCredito;
import com.fkmanager360.credito.domain.PoliticaCreditoV1;
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

    /**
     * A partir do refactor para Spring Data JPA, a guarda deixa de ser "JPA nao existe neste
     * modulo" (contradiria a decisao do Owner de adotar JPA como padrao de persistencia) e passa a
     * provar, estruturalmente, ONDE JPA pode viver: nunca dentro do hexagono
     * ({@link #dominio_e_aplicacao_nao_dependem_de_persistencia}), e somente dentro de
     * {@code adapter.out.persistence} ({@link #jpa_somente_na_persistencia}).
     */
    @ArchTest
    static final ArchRule dominio_e_aplicacao_nao_dependem_de_persistencia =
            noClasses().that().resideInAnyPackage("..domain..", "..application..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "jakarta.persistence..", "org.hibernate..", "org.springframework.data..");

    @ArchTest
    static final ArchRule jpa_somente_na_persistencia =
            noClasses().that().resideOutsideOfPackage("..adapter.out.persistence..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "jakarta.persistence..", "org.hibernate..", "org.springframework.data..");

    /**
     * Companheira das duas regras acima: a borda web nunca pula a port de aplicacao para falar
     * direto com um repository Spring Data -- reforca que {@code adapter.in.web} so conhece
     * {@code application.port.in}/{@code application.usecase}, nunca {@code adapter.out.persistence}.
     */
    @ArchTest
    static final ArchRule web_nao_acessa_repository =
            noClasses().that().resideInAPackage("..adapter.in.web..")
                    .should().dependOnClassesThat().resideInAPackage("..adapter.out.persistence..");

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
     * A partir de #0003, {@code credito_db} e a persistencia existem deliberadamente (ADR-0010,
     * ADR-0014) -- as duas guardas anteriores, que afirmavam a AUSENCIA de estado duravel, foram
     * removidas (inverte-las para "o recurso existe" seria tautologico: passaria so por existir o
     * arquivo que este mesmo ticket acabou de criar). No lugar delas, S8 passa a provar a forma
     * como a persistencia entra: {@code org.springframework.transaction..} e o que sustenta
     * "nenhuma chamada remota com transacao aberta" (plano #0003, secao 1) -- se aparecer fora de
     * {@code adapter.out.persistence}, alguma camada de fora esta abrindo transacao por conta
     * propria.
     */
    @ArchTest
    static final ArchRule transacao_somente_na_persistencia =
            noClasses().that().resideOutsideOfPackage("..adapter.out.persistence..")
                    .should().dependOnClassesThat().resideInAPackage("org.springframework.transaction..");

    /**
     * Companheira da regra acima: JDBC (puro ou via Spring) so pode aparecer onde a persistencia
     * vive. Garante que nenhum adapter de entrada, caso de uso ou classe de dominio decida ir
     * direto ao banco por fora da porta de saida.
     */
    @ArchTest
    static final ArchRule jdbc_somente_na_persistencia =
            noClasses().that().resideOutsideOfPackage("..adapter.out.persistence..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "java.sql..", "javax.sql..", "org.springframework.jdbc..");

    /**
     * Flyway aqui e autoconfiguracao mais recursos SQL versionados em {@code db/migration}:
     * nenhuma classe deste modulo o invoca programaticamente. Esta regra falharia no dia em que
     * alguem introduzisse migracao programatica em runtime -- o que o ADR-0014 (emenda
     * 2026-09-02) reserva deliberadamente ao Flyway embutido no boot, nunca a codigo nosso.
     */
    @ArchTest
    static final ArchRule nenhuma_classe_depende_de_flyway =
            noClasses().should().dependOnClassesThat().resideInAPackage("org.flywaydb..");

    /**
     * D6 do plano #0003, estrutural: o adapter de persistencia recebe uma DecisaoCredito JA
     * CALCULADA e nunca decide credito por conta propria. Se algum dia
     * {@code adapter.out.persistence} importar {@link MotorDecisaoCredito} ou qualquer
     * {@link PoliticaCredito}, a regra ArchUnit falha antes que o code review precise pegar isso.
     */
    @ArchTest
    static final ArchRule persistencia_nao_conhece_motor_de_decisao_nem_politica =
            noClasses().that().resideInAPackage("..adapter.out.persistence..")
                    .should().dependOnClassesThat().belongToAnyOf(
                            MotorDecisaoCredito.class, PoliticaCredito.class, PoliticaCreditoV1.class);

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

    /**
     * Plano #0003, secao 10 / ADR-0017 / ADR-0020: instrumentacao e preocupacao de adapter, nunca
     * de dominio. {@code decisoes_credito_total} e registrado em {@code adapter.in.web}
     * ({@code MetricasDecisaoCredito}); se algum dia {@code io.micrometer..} aparecer dentro de
     * {@code domain}, esta regra falha antes que o code review precise pegar isso.
     */
    @ArchTest
    static final ArchRule dominio_nao_depende_de_micrometer =
            noClasses().that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat().resideInAnyPackage("io.micrometer..");

    /**
     * Falsifica: um membro gerado por Lombok (marcado com {@code @lombok.Generated}, retencao
     * CLASS -- {@code lombok.config} na raiz liga {@code addLombokGeneratedAnnotation}) declarado
     * numa classe de dominio ou aplicacao. Provado empiricamente falsificavel neste modulo:
     * anotar temporariamente uma classe de {@code application.usecase} com
     * {@code @RequiredArgsConstructor} e rodar este teste produz vermelho -- ArchUnit le a
     * anotacao CLASS-retention pelo bytecode via ASM, sem depender de reflection em runtime (que
     * so enxergaria RUNTIME-retention). A norma "hexagono sem Lombok" continua valendo por
     * construcao e revisao mesmo fora deste teste; esta regra so a torna tambem verificavel por
     * bytecode.
     */
    @ArchTest
    static final ArchRule hexagono_nao_usa_lombok =
            noMembers().that().areDeclaredInClassesThat().resideInAnyPackage("..domain..", "..application..")
                    .should().beAnnotatedWith("lombok.Generated");
}
