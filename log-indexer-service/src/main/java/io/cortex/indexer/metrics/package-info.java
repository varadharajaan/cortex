/**
 * Micrometer instrumentation for the CORTEX log-indexer-service
 * (P7 epic).
 *
 * <p>Hosts {@link io.cortex.indexer.metrics.IndexerMetrics} -- the
 * OCP-bootstrap-registered counter family
 * {@code cortex.indexer.index_admin_total{backend, outcome, tenant_id}}
 * subscribed by Grafana dashboards from P7.0 onwards. Per Part 17
 * tag-cardinality contract only the three allowlisted tag keys are
 * emitted on this counter.</p>
 */
package io.cortex.indexer.metrics;
