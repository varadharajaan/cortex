package io.cortex.remediation.anomaly;

import io.cortex.remediation.parse.AnomalyEvent;
import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fail-open writer for the anomaly read model (P9.3 prereq).
 *
 * <p>The remediation engine remains the authoritative action path.
 * This writer records a query copy for P9.3a, but a database outage
 * must not block engine handling or Kafka acknowledgment.</p>
 */
@Service
@Slf4j
public class AnomalyReadModelWriter {

    private final AnomaliesRepository repository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    /**
     * Spring constructor.
     *
     * @param repository         anomaly repository
     * @param transactionManager transaction manager for one-row inserts
     */
    @Autowired public AnomalyReadModelWriter(final AnomaliesRepository repository,
            final PlatformTransactionManager transactionManager) {
        this(repository, transactionManager, Clock.systemUTC());
    }

    AnomalyReadModelWriter(final AnomaliesRepository repository,
                           final PlatformTransactionManager transactionManager,
                           final Clock clock) {
        this.repository = repository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setName("anomaly-read-model-insert");
        this.clock = clock;
    }

    /**
     * Persists the parsed anomaly in a short transaction and swallows
     * failures after logging them.
     *
     * @param event parsed anomaly from {@code cortex.anomalies.v1}
     */
    public void persistFailOpen(final AnomalyEvent event) {
        try {
            final AnomalyRecord record = AnomalyRecord.from(event, this.clock.instant());
            final Boolean inserted = this.transactionTemplate.execute(status ->
                    this.repository.insertIfAbsent(record));
            if (Boolean.FALSE.equals(inserted)) {
                log.debug("Anomaly read-model duplicate ignored eventId={} tenantId={}",
                        event.eventId(), event.tenantId());
            }
        } catch (RuntimeException ex) {
            log.error("Failed to persist anomaly read model eventId={} tenantId={}",
                    event.eventId(), event.tenantId(), ex);
        }
    }
}
