package io.cortex.indexer;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

/**
 * ArchUnit layer-purity test for log-indexer-service (P7.0).
 *
 * <p>Enforces the SPI seam: only App + Metrics + Health may reach
 * the {@code admin} layer (Quickwit admin SPI + DTOs + noop default
 * impl); Metrics is reached by App only; Health is reached by App
 * only. Same layered-architecture pattern P5.0 + P6.0 used to lock
 * the consume / classify / dispatch layers in
 * log-processor-service + log-remediation-service.</p>
 *
 * <p>This is a layered-architecture contract, not a no-cycles
 * check: cycles already get caught by Maven's
 * {@code dependencyConvergence} on the parent enforcer.</p>
 */
class ArchitectureTest {

    /** Asserts the layered architecture contract for io.cortex.indexer packages. */
    @Test
    void layersAreClean() {
        final JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.cortex.indexer");

        final ArchRule layered = layeredArchitecture()
                .consideringAllDependencies()
                .layer("App").definedBy("io.cortex.indexer")
                .layer("Admin").definedBy("io.cortex.indexer.admin..")
                .layer("Metrics").definedBy("io.cortex.indexer.metrics..")
                .layer("Health").definedBy("io.cortex.indexer.health..")

                // Admin is the SPI - reached by App + Metrics (bootstrap
                // loops over List<QuickwitIndexAdmin>) + Health
                // (surfaces backendId as a detail).
                .whereLayer("Admin")
                .mayOnlyBeAccessedByLayers("App", "Metrics", "Health")
                // Metrics is reached by App + Admin (P7.1 -
                // QuickwitHttpAdmin ticks the cortex.indexer.index_admin_total
                // counter after every admin call per ADR-0039).
                .whereLayer("Metrics").mayOnlyBeAccessedByLayers("App", "Admin")
                // Health is reached by App only.
                .whereLayer("Health").mayOnlyBeAccessedByLayers("App");

        layered.check(classes);
    }
}
