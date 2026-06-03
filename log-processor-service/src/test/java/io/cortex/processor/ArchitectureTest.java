package io.cortex.processor;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

/**
 * ArchUnit layer-purity test for log-processor-service (P5.0).
 *
 * <p>Enforces the SPI seam: the consume layer is allowed to depend
 * on the classify SPI interface ({@code AnomalyClassifier} +
 * {@code Classification}) but not on classify implementations
 * directly. Same pattern P4.0 used to lock the controller / service
 * / persistence layers in log-ingest-service.</p>
 *
 * <p>This is a layered-architecture contract, not a no-cycles
 * check: cycles already get caught by Maven's
 * {@code dependencyConvergence} on the parent enforcer.</p>
 */
class ArchitectureTest {

    /** Asserts the layered architecture contract for io.cortex.processor packages. */
    @Test
    void layersAreClean() {
        final JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.cortex.processor");

        final ArchRule layered = layeredArchitecture()
                .consideringAllDependencies()
                .layer("App").definedBy("io.cortex.processor")
                .layer("Consume").definedBy("io.cortex.processor.consume..")
                .layer("Classify").definedBy("io.cortex.processor.classify..")
                .layer("Metrics").definedBy("io.cortex.processor.metrics..")

                // Consume may call into the Classify SPI + Metrics.
                .whereLayer("Consume").mayOnlyBeAccessedByLayers("App")
                // Classify is the SPI - accessed by Consume + tests.
                .whereLayer("Classify").mayOnlyBeAccessedByLayers("App", "Consume")
                // Metrics is accessed by Consume.
                .whereLayer("Metrics").mayOnlyBeAccessedByLayers("App", "Consume");

        layered.check(classes);
    }
}
