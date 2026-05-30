package io.cortex.gateway.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_JODATIME;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.Architectures;

/**
 * Enforces CORTEX layering, naming, and coding rules at build time
 * (PART 8 of the strict-rules contract, rule 8.14 mandates a test per
 * rule).
 */
@AnalyzeClasses(
        packages = "io.cortex.gateway",
        importOptions = {ImportOption.DoNotIncludeTests.class})
public final class ArchitectureRulesTest {

    /** Rule 8.1 / 8.2 / 8.3: enforce the controller-service-repository layering.
     *  P3.1 re-adds the Service layer (P3.0 omitted it temporarily because
     *  ArchUnit rejects empty layers; see memory.md EQ8). */
    @ArchTest
    static final ArchRule LAYERING = Architectures.layeredArchitecture().consideringAllDependencies()
            .layer("Controller").definedBy("..controller..")
            .layer("Service").definedBy("..service..", "..service.impl..")
            .layer("Filter").definedBy("..filter..")
            .layer("Config").definedBy("..config..")
            .layer("Security").definedBy("..security..")
            .layer("Exception").definedBy("..exception..")
            .layer("Dto").definedBy("..dto..")
            .layer("Constants").definedBy("..constants..")

            .whereLayer("Controller").mayNotBeAccessedByAnyLayer()
            .whereLayer("Service").mayOnlyBeAccessedByLayers("Controller", "Service")
            .whereLayer("Exception").mayOnlyBeAccessedByLayers(
                    "Controller", "Service", "Filter", "Config", "Security")
            .whereLayer("Filter").mayNotBeAccessedByAnyLayer();

    /** Rule 8.4: DTOs must be records so they are immutable by construction. */
    @ArchTest
    static final ArchRule DTOS_ARE_RECORDS = classes()
            .that().resideInAPackage("..dto..")
            .and().areNotAnnotations()
            .and().areNotInterfaces()
            .and().haveSimpleNameNotEndingWith("package-info")
            .should().beRecords()
            .because("rule 8.4 - DTOs are immutable records");

    /** Rule 8.12 / 14.1: no field-level @Autowired; constructor injection only. */
    @ArchTest
    static final ArchRule NO_FIELD_AUTOWIRED = noFields()
            .should().beAnnotatedWith("org.springframework.beans.factory.annotation.Autowired")
            .because("rule 14.1 - constructor injection only");

    /** Rule A8.6 / 8.11: no console writes. */
    @ArchTest
    static final ArchRule NO_STANDARD_STREAMS = NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

    /** Rule A8.4: no java.util.logging; SLF4J only. */
    @ArchTest
    static final ArchRule NO_JUL = NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

    /** Rule 8.10: no Joda-Time; java.time only. */
    @ArchTest
    static final ArchRule NO_JODA = NO_CLASSES_SHOULD_USE_JODATIME;

    /** Rule 8.10: no java.util.Date in CORTEX code; java.time only. */
    @ArchTest
    static final ArchRule NO_JAVA_UTIL_DATE = noClasses()
            .that().resideInAPackage("io.cortex..")
            .should().dependOnClassesThat().haveFullyQualifiedName("java.util.Date")
            .because("rule 8.10 - java.time only");

    /** Rule 18.x: production code must not depend on Spring's RestTemplate (deprecated path). */
    @ArchTest
    static final ArchRule NO_REST_TEMPLATE = noClasses()
            .that().resideInAPackage("io.cortex.gateway..")
            .should().dependOnClassesThat().haveFullyQualifiedName(
                    "org.springframework.web.client.RestTemplate")
            .because("RestTemplate is in maintenance mode; use RestClient or WebClient");

    /** Constants holders are final. */
    @ArchTest
    static final ArchRule CONSTANTS_ARE_FINAL = classes()
            .that().resideInAPackage("..constants..")
            .and().areNotEnums()
            .and().haveSimpleNameNotEndingWith("package-info")
            .should().haveModifier(com.tngtech.archunit.core.domain.JavaModifier.FINAL)
            .because("rule 8.8 - utility / constants holders are final");

    /** Logger fields must be private static final per rule A8.7. */
    @ArchTest
    static final ArchRule LOGGERS_ARE_PRIVATE_STATIC_FINAL = fields()
            .that().haveRawType("org.slf4j.Logger")
            .should().bePrivate()
            .andShould().beStatic()
            .andShould().beFinal()
            .because("rule A8.7 - loggers are private static final");
}
