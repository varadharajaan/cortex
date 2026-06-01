/**
 * Servlet filters running at the front of every request: correlation
 * id propagation (rule 17.5 / A8.2). Filters are stateless and ordered
 * via {@link org.springframework.core.Ordered}.
 */
package io.cortex.ingest.filter;
