/**
 * Owns: JWT issuance, JWT decoding, refresh-token state, and Spring Security
 * adapters that map JWT claims to {@code GrantedAuthority} instances.
 * Never imports: controllers, persistence framework, DTO classes.
 * Owner: CORTEX :: log-gateway.
 */
package io.cortex.gateway.security;
