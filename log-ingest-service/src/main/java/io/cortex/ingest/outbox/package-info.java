/**
 * Transactional outbox for the log-ingest service (P4.4 / ADR-0025).
 *
 * <p>Every persisted {@code raw_logs} row writes a sibling
 * {@code outbox_events} row inside the SAME per-row REQUIRES_NEW
 * transaction so the system-of-record and the outbox can never
 * drift (strict rule B10.1). A scheduled poller (P4.4b) sweeps
 * {@code status = 'PENDING'} rows, wraps each in a CloudEvents 1.0
 * envelope, and publishes via Spring Cloud Stream to the
 * {@code cortex.logs.events.v1} topic. Cold-path dedupe rolls back
 * both inserts atomically; the outbox cannot publish a duplicate.</p>
 */
package io.cortex.ingest.outbox;
