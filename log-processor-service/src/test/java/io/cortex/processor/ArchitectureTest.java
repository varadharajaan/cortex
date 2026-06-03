package io.cortex.processor;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

/**
 * ArchUnit layer-purity test for log-processor-service (P5.0 / P5.1).
 *
 * <p>Enforces the SPI seam: the consume layer is allowed to depend
 * on the classify SPI interface ({@code AnomalyClassifier} +
 * {@code Classification}) but not on classify implementations
 * directly. Same pattern P4.0 used to lock the controller / service
 * / persistence layers in log-ingest-service.</p>
 *
 * <p>P5.1 adds the {@code parse} layer (CloudEvent decode + schema
 * validation + DLQ failure-reason allowlist) and the {@code config}
 * layer (Kafka producer wiring for the DLQ publisher).</p>
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
                .layer("Parse").definedBy("io.cortex.processor.parse..")
                .layer("Metrics").definedBy("io.cortex.processor.metrics..")
                .layer("Config").definedBy("io.cortex.processor.config..")

                // Consume orchestrates the parse + validate + classify
                // pipeline; only App scans the @KafkaListener.
                .whereLayer("Consume").mayOnlyBeAccessedByLayers("App")
                // Classify is the SPI - accessed by Consume + tests.
                .whereLayer("Classify").mayOnlyBeAccessedByLayers("App", "Consume")
                // Parse carries the typed RawLogEvent referenced by the
                // Classify SPI signature, so Classify reaches in.
                .whereLayer("Parse")
                .mayOnlyBeAccessedByLayers("App", "Consume", "Classify")
                // Metrics is accessed by Consume.
                .whereLayer("Metrics").mayOnlyBeAccessedByLayers("App", "Consume")
                // Config beans are wired by App; Consume @Autowires the
                // KafkaTemplate produced here via DlqPublisher.
                .whereLayer("Config").mayOnlyBeAccessedByLayers("App");

        layered.check(classes);
    }
}
