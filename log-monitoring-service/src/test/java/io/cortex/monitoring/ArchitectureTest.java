package io.cortex.monitoring;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

/**
 * ArchUnit layer-purity test for log-monitoring-service (P8.0).
 *
 * <p>Enforces the SPI seam: only App + Metrics + Health may reach
 * the {@code probe} layer (ServiceHealthProbe SPI + DTOs + noop
 * default impl); Metrics is reached by App only; Health is
 * reached by App only. Same layered-architecture pattern P5.0 +
 * P6.0 + P7.0 used to lock the consume / classify / dispatch /
 * admin layers in log-processor-service +
 * log-remediation-service + log-indexer-service.</p>
 *
 * <p>This is a layered-architecture contract, not a no-cycles
 * check: cycles already get caught by Maven's
 * {@code dependencyConvergence} on the parent enforcer.</p>
 */
class ArchitectureTest {

    /** Asserts the layered architecture contract for io.cortex.monitoring packages. */
    @Test
    void layersAreClean() {
        final JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.cortex.monitoring");

        final ArchRule layered = layeredArchitecture()
                .consideringAllDependencies()
                .layer("App").definedBy("io.cortex.monitoring")
                .layer("Constants").definedBy("io.cortex.monitoring.constants..")
                .layer("Probe").definedBy("io.cortex.monitoring.probe..")
                .layer("Metrics").definedBy("io.cortex.monitoring.metrics..")
                .layer("Health").definedBy("io.cortex.monitoring.health..")

                // Probe is the SPI - reached by App + Metrics
                // (bootstrap loops over List<ServiceHealthProbe>) +
                // Health (surfaces backendId as a detail).
                .whereLayer("Probe")
                .mayOnlyBeAccessedByLayers("App", "Metrics", "Health")
                // Metrics is reached by App + Probe (P8.1 adapters tick
                // the counter after every probe call via the @Lazy
                // back-edge -- LD131).
                .whereLayer("Metrics").mayOnlyBeAccessedByLayers("App", "Probe")
                // Constants is reached by Probe (RestProbeTemplate uses
                // the HTTP status floors).
                .whereLayer("Constants").mayOnlyBeAccessedByLayers("Probe")
                // Health is reached by App only.
                .whereLayer("Health").mayOnlyBeAccessedByLayers("App");

        layered.check(classes);
    }
}
