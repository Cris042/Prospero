package br.prospero.bo;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

@AnalyzeClasses(packages = "br.prospero.bo", importOptions = ImportOption.DoNotIncludeTests.class)
class ArquiteturaTest {
    @ArchTest
    static final ArchRule dominioSemRelogioDireto = noClasses().that()
            .resideInAnyPackage("..dominio..", "..helper..")
            .should().callMethodWhere(new DescribedPredicate<JavaMethodCall>("obtém now() de java.time") {
                @Override public boolean test(JavaMethodCall chamada) {
                    return chamada.getTarget().getName().equals("now")
                            && chamada.getTarget().getOwner().getPackageName().startsWith("java.time");
                }
            });

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
            .should().dependOnClassesThat().resideInAnyPackage("br.prospero.fo..", "br.prospero.mo..", "br.prospero.me..");

    @ArchTest
    static final ArchRule dominioSemDouble = noFields().that().areDeclaredInClassesThat()
            .resideInAPackage("..dominio..").should().haveRawType(double.class).allowEmptyShould(true);

    @ArchTest
    static final ArchRule dominioSemDoubleWrapper = noFields().that().areDeclaredInClassesThat()
            .resideInAPackage("..dominio..").should().haveRawType(Double.class).allowEmptyShould(true);
}
