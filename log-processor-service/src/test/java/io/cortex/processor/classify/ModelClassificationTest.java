package io.cortex.processor.classify;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link ModelClassification} record (P5.2 / ADR-0029).
 *
 * <p>Exercises the {@code @JsonCreator} constructor branches; the
 * canonical record constructor is already covered by
 * {@link SpringAiAnomalyClassifierTest}. The JsonCreator path is
 * what Spring AI's {@code BeanOutputConverter} invokes when it
 * deserialises the model JSON response, so the null-tolerant
 * defaults need branch coverage of their own.</p>
 */
class ModelClassificationTest {

    /**
     * JsonCreator with every field populated keeps the supplied
     * values verbatim.
     */
    @Test
    void jsonCreatorWithAllFieldsPopulatedKeepsValues() {
        final ModelClassification entity = new ModelClassification(
                true, "HIGH", "elevated 500s in payments", Double.valueOf(0.85d));

        assertThat(entity.anomaly()).isTrue();
        assertThat(entity.severity()).isEqualTo("HIGH");
        assertThat(entity.reason()).isEqualTo("elevated 500s in payments");
        assertThat(entity.confidence()).isEqualTo(0.85d);
    }

    /**
     * JsonCreator with all nullable fields set to {@code null}
     * applies the documented defaults ({@code NONE} severity,
     * empty reason, {@code 0.0} confidence) so an under-populated
     * model response never trips an NPE inside the classifier.
     */
    @Test
    void jsonCreatorWithNullFieldsAppliesDefaults() {
        final ModelClassification entity = new ModelClassification(
                false, null, null, (Double) null);

        assertThat(entity.anomaly()).isFalse();
        assertThat(entity.severity()).isEqualTo("NONE");
        assertThat(entity.reason()).isEmpty();
        assertThat(entity.confidence()).isEqualTo(0.0d);
    }
}
