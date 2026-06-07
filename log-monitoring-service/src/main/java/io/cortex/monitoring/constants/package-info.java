/**
 * Shared HTTP constants for every
 * {@link io.cortex.monitoring.probe.ServiceHealthProbe} adapter
 * (P8.1 / ADR-0045).
 *
 * <p>Holds the {@code 429}, {@code 5xx}, and {@code 404} magic
 * numbers centrally per Rule A7 so adapters and tests dereference
 * one place. Symmetric to
 * {@code io.cortex.indexer.constants}.</p>
 */
package io.cortex.monitoring.constants;
