package io.cortex.remediation.dlq;

import io.cortex.remediation.parse.FailureReason;
import org.apache.kafka.clients.consumer.ConsumerRecord;

/**
 * Publishes malformed anomaly records to a bounded poison-message lane.
 */
public interface AnomalyDlqPublisher {

    /**
     * Publish the original record to the anomaly DLQ.
     *
     * @param record original Kafka record
     * @param reason parse failure reason
     * @param message short diagnostic message
     */
    void publish(ConsumerRecord<byte[], byte[]> record, FailureReason reason,
                 String message);
}
