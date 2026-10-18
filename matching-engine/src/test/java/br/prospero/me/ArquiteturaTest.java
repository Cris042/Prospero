package br.prospero.me;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

@AnalyzeClasses(packages = "br.prospero.me", importOptions = ImportOption.DoNotIncludeTests.class)
class ArquiteturaTest {
    @ArchTest
    static final ArchRule restNaoAcessaJpa = noClasses().that().resideInAPackage("..rest..")
            .should().dependOnClassesThat().resideInAnyPackage("..jpa..", "jakarta.persistence..");

    @ArchTest
    static final ArchRule dominioEHelpersSaoPuros = noClasses().that()
            .resideInAnyPackage("..dominio..", "..helper..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "io.quarkus..", "jakarta.persistence..", "jakarta.ws.rs..", "jakarta.inject..",
                    "jakarta.enterprise..", "org.hibernate..", "org.apache.kafka..", "java.sql..", "java.net..")
            .allowEmptyShould(true);

    /** RNF02 (ADR-013): o núcleo do matching não lê relógio nem sorteia; tempo e IDs vêm dos comandos. */
    @ArchTest
    static final ArchRule nucleoDeterministico = noClasses().that().resideInAPackage("..livro..")
            .should().callMethod(java.util.UUID.class, "randomUUID")
            .orShould().callMethod(java.time.Instant.class, "now")
            .orShould().callMethod(java.time.LocalDate.class, "now")
            .orShould().callMethod(System.class, "currentTimeMillis")
            .orShould().callMethod(System.class, "nanoTime")
            .orShould().dependOnClassesThat().areAssignableTo(java.util.Random.class)
            .orShould().dependOnClassesThat().areAssignableTo(java.time.Clock.class);

    @ArchTest
    static final ArchRule naoDependeDeOutrosContextos = noClasses()
            .should().dependOnClassesThat().resideInAnyPackage("br.prospero.fo..", "br.prospero.mo..", "br.prospero.bo..");

    @ArchTest
    static final ArchRule dominioSemDouble = noFields().that().areDeclaredInClassesThat()
            .resideInAPackage("..dominio..").should().haveRawType(double.class).allowEmptyShould(true);

    @ArchTest
    static final ArchRule dominioSemDoubleWrapper = noFields().that().areDeclaredInClassesThat()
            .resideInAPackage("..dominio..").should().haveRawType(Double.class).allowEmptyShould(true);
}
