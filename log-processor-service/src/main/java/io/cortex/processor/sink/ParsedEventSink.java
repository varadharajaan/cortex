package io.cortex.processor.sink;

import io.cortex.processor.classify.Classification;
import io.cortex.processor.parse.RawLogEvent;

/**
 * SPI for the P5.3 fan-out sinks (ADR-0030).
 *
 * <p>Implementations publish a single parsed + validated
 * {@link RawLogEvent} (paired with the
 * {@link io.cortex.processor.classify.AnomalyClassifier} verdict)
 * to an external observability tier. Invoked from
 * {@code LogEventConsumer} <strong>after</strong> the classify step
 * and <strong>before</strong> the manual Kafka offset commit. The
 * call MUST be fire-and-forget: any failure is swallowed and surfaced
 * as a counter tick + WARN log so a sink outage cannot rewind Kafka
 * offsets (ADR-0030 D2, mirror of ADR-0029 D4).</p>
 *
 * <p>Implementations are gated by
 * {@code @ConditionalOnProperty(name="cortex.processor.sinks.&lt;impl&gt;.enabled",
 * havingValue="true")} so the default dev boot stays sink-free.</p>
 */
public interface ParsedEventSink {

    /**
     * Fan out the supplied event + classifier verdict to the
     * downstream tier. Implementations MUST NOT throw on failure;
     * tick the {@code failed_total} counter via {@link SinkMetrics}
     * and log a WARN instead.
     *
     * @param event   the parsed + validated source event
     * @param verdict the classifier verdict (may be
     *                {@link Classification#none()} for normal events)
     */
    void send(RawLogEvent event, Classification verdict);

    /**
     * Stable name reported in Micrometer tag values + WARN logs;
     * implementations return a constant slug ({@code "loki"},
     * {@code "quickwit"}).
     *
     * @return the sink identifier
     */
    String name();
}
