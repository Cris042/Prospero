package br.prospero.mo;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

@AnalyzeClasses(packages = "br.prospero.mo", importOptions = ImportOption.DoNotIncludeTests.class)
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

    @ArchTest
    static final ArchRule naoDependeDeOutrosContextos = noClasses()
            .should().dependOnClassesThat().resideInAnyPackage("br.prospero.fo..", "br.prospero.me..", "br.prospero.bo..");

    @ArchTest
    static final ArchRule dominioSemDouble = noFields().that().areDeclaredInClassesThat()
            .resideInAPackage("..dominio..").should().haveRawType(double.class).allowEmptyShould(true);

    @ArchTest
    static final ArchRule dominioSemDoubleWrapper = noFields().that().areDeclaredInClassesThat()
            .resideInAPackage("..dominio..").should().haveRawType(Double.class).allowEmptyShould(true);
}
