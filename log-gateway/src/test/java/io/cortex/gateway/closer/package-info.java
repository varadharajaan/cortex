/**
 * Owns: cross-surface integration tests that prove two CORTEX surfaces
 * agree on payload semantics for the same input. Naming convention is
 * {@code <feature>RestAndGraphQlParityIT}; all tests in this package
 * are Failsafe {@code *IT} closers and bootstrap the full Spring
 * context (P9.0 / ADR-0049 / LD104).
 *
 * <p>Never imports: repositories, JPA entities, persistence framework.
 * The closer asserts CONTRACT parity; component-level coverage lives
 * in the per-surface slice tests.</p>
 *
 * <p>Owner: CORTEX :: log-gateway.</p>
 */
package io.cortex.gateway.closer;
