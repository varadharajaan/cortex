package io.cortex.remediation;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

/**
 * ArchUnit layer-purity test for log-remediation-service (P6.0).
 *
 * <p>Enforces the SPI seam: the consume layer is allowed to depend
 * on the dispatch SPI interface
 * ({@code RemediationDispatcher} + {@code DispatchResult}) but not
 * on dispatch implementations directly. Same pattern P5.0 used to
 * lock the consume / classify / metrics layers in
 * log-processor-service.</p>
 *
 * <p>{@code parse} hosts the typed {@link io.cortex.remediation.parse.AnomalyEvent}
 * record + the {@code AnomalyEnvelopeParser} CloudEvents decoder. The
 * {@link io.cortex.remediation.dispatch.RemediationDispatcher} SPI
 * signature references {@code AnomalyEvent}, so {@code Parse} is
 * reachable from both {@code Consume} and {@code Dispatch}.</p>
 *
 * <p>This is a layered-architecture contract, not a no-cycles
 * check: cycles already get caught by Maven's
 * {@code dependencyConvergence} on the parent enforcer.</p>
 */
class ArchitectureTest {

    /** Asserts the layered architecture contract for io.cortex.remediation packages. */
    @Test
    void layersAreClean() {
        final JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.cortex.remediation");

        final ArchRule layered = layeredArchitecture()
                .consideringAllDependencies()
                .layer("App").definedBy("io.cortex.remediation")
                .layer("Consume").definedBy("io.cortex.remediation.consume..")
                .layer("Parse").definedBy("io.cortex.remediation.parse..")
                .layer("Dispatch").definedBy("io.cortex.remediation.dispatch..")
                .layer("Metrics").definedBy("io.cortex.remediation.metrics..")

                // Consume orchestrates the parse + dispatch pipeline;
                // only App scans the @KafkaListener.
                .whereLayer("Consume").mayOnlyBeAccessedByLayers("App")
                // Parse carries the typed AnomalyEvent referenced by
                // the Dispatch SPI signature, so both Consume + Dispatch
                // reach in.
                .whereLayer("Parse")
                .mayOnlyBeAccessedByLayers("App", "Consume", "Dispatch")
                // Dispatch is the SPI - accessed by Consume + tests.
                .whereLayer("Dispatch").mayOnlyBeAccessedByLayers("App", "Consume")
                // Metrics is accessed by Consume.
                .whereLayer("Metrics").mayOnlyBeAccessedByLayers("App", "Consume");

        layered.check(classes);
    }
}
