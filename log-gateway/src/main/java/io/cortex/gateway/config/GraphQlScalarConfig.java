package io.cortex.gateway.config;

import graphql.scalars.ExtendedScalars;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.execution.RuntimeWiringConfigurer;

/**
 * Registers the custom GraphQL scalars used by the read-side schema
 * (P9.1b / ADR-0049).
 *
 * <p>The {@code searchLogs} result exposes Quickwit hit documents as
 * opaque JSON objects so the gateway does not couple itself to the
 * indexer's document schema. GraphQL has no built-in object/JSON or
 * 64-bit integer scalar, so the {@code JSON} and {@code Long} scalars
 * from {@code graphql-java-extended-scalars} are wired here. The
 * configurer is unconditional because {@code schema.graphqls} always
 * declares {@code scalar JSON} and {@code scalar Long}; leaving them
 * unwired would fail schema assembly at startup even when the
 * {@code searchLogs} resolver is disabled.</p>
 */
@Configuration
public class GraphQlScalarConfig {

    /**
     * Contributes the {@code JSON} and {@code Long} scalar wirings to
     * the schema.
     *
     * @return a {@link RuntimeWiringConfigurer} registering both scalars
     */
    @Bean
    public RuntimeWiringConfigurer searchLogsScalarConfigurer() {
        return wiringBuilder -> wiringBuilder
                .scalar(ExtendedScalars.Json)
                .scalar(ExtendedScalars.GraphQLLong);
    }
}
