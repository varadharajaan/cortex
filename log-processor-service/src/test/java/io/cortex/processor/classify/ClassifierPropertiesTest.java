package io.cortex.processor.classify;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.Resource;

/**
 * Unit test for {@link ClassifierProperties} binding
 * (P5.2 / ADR-0029).
 *
 * <p>Exercises the typed {@link ClassifierProperties} record under
 * two scenarios:</p>
 * <ol>
 *   <li>defaults bind cleanly when no properties are supplied</li>
 *   <li>explicit property overrides win</li>
 * </ol>
 */
class ClassifierPropertiesTest {

    /** Spring context runner under test (no Spring AI starter on this classpath slice). */
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(EnablePropertiesConfig.class);

    /** Defaults: all-null inputs coerce to the constants documented on the record. */
    @Test
    void defaultsBindToDocumentedConstants() {
        this.contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ClassifierProperties.class);
            final ClassifierProperties properties = context.getBean(ClassifierProperties.class);
            assertThat(properties.provider()).isEqualTo(ClassifierProperties.DEFAULT_PROVIDER);
            assertThat(properties.temperature())
                    .isEqualTo(ClassifierProperties.DEFAULT_TEMPERATURE);
            assertThat(properties.maxTokens())
                    .isEqualTo(ClassifierProperties.DEFAULT_MAX_TOKENS);
            assertThat(properties.confidenceThreshold())
                    .isEqualTo(ClassifierProperties.DEFAULT_CONFIDENCE_THRESHOLD);
            assertThat(properties.requestTimeout())
                    .isEqualTo(ClassifierProperties.DEFAULT_REQUEST_TIMEOUT);
        });
    }

    /** Overrides: explicit properties bind through to the record. */
    @Test
    void explicitPropertiesOverrideDefaults() {
        this.contextRunner
                .withPropertyValues(
                        "cortex.processor.classifier.provider=spring-ai",
                        "cortex.processor.classifier.model=llama3.1:8b",
                        "cortex.processor.classifier.temperature=0.05",
                        "cortex.processor.classifier.max-tokens=512",
                        "cortex.processor.classifier.confidence-threshold=0.9",
                        "cortex.processor.classifier.request-timeout=PT30S",
                        "cortex.processor.classifier.prompt-template=classpath:/prompts/anomaly-classifier.st")
                .run(context -> {
                    final ClassifierProperties properties =
                            context.getBean(ClassifierProperties.class);
                    assertThat(properties.provider()).isEqualTo("spring-ai");
                    assertThat(properties.model()).isEqualTo("llama3.1:8b");
                    assertThat(properties.temperature()).isEqualTo(0.05d);
                    assertThat(properties.maxTokens()).isEqualTo(512);
                    assertThat(properties.confidenceThreshold()).isEqualTo(0.9d);
                    assertThat(properties.requestTimeout()).isEqualTo(Duration.ofSeconds(30));
                    final Resource template = properties.promptTemplate();
                    assertThat(template).isNotNull();
                    assertThat(template.exists()).isTrue();
                });
    }

    /** Minimal configuration class that activates the typed binding. */
    @EnableConfigurationProperties(ClassifierProperties.class)
    static class EnablePropertiesConfig {
    }
}
