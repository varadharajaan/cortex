/**
 * Owns: Spring for GraphQL resolvers (P9.0 / ADR-0049). One
 * {@code @Controller} class per top-level query field, each one a thin
 * dispatcher that translates the GraphQL input into the SAME service
 * call used by the matching REST controller -- no business logic lives
 * here.
 *
 * <p>Never imports: repositories, JPA entities, persistence framework.
 * Mutations are forever rejected per ADR-0004 (rejected alternative
 * RA5); only Query types are declared in
 * {@code src/main/resources/graphql/schema.graphqls}.</p>
 *
 * <p>Owner: CORTEX :: log-gateway.</p>
 */
package io.cortex.gateway.graphql;
