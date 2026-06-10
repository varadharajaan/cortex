package io.cortex.monitoring.slo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * P8.5 backend that evaluates SLOs from Prometheus instant queries.
 */
@Component
@ConditionalOnExpression(
        "'${cortex.monitoring.slo.backend:noop}' == 'promql'"
                + " || '${cortex.monitoring.slo.backend:noop}' == 'mixed'")
public final class PromQlSloBudgetEngine implements SloBudgetEngine {

    private static final String PROMETHEUS_QUERY_PATH = "/api/v1/query";
    private static final String STATUS_SUCCESS = "success";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    /**
     * Spring constructor.
     *
     * @param restClient named Prometheus API client
     * @param mapper JSON parser
     */
    public PromQlSloBudgetEngine(
            @Qualifier("promQlSloRestClient") final RestClient restClient,
            final ObjectMapper mapper) {
        this.restClient = restClient;
        this.objectMapper = mapper;
    }

    @Override
    public String backendId() {
        return SloSnapshot.BACKEND_PROMQL;
    }

    @Override
    public boolean supports(final SloDefinition def) {
        return def != null && def.promQl() != null;
    }

    @Override
    public SloSnapshot evaluate(final SloDefinition def) {
        try {
            if (def == null || def.promQl() == null) {
                return SloSnapshot.permanentFailure(backendId(), def,
                        backendId() + ":missing-promql-source");
            }
            final double successes = queryValue(def.promQl().successQuery());
            final double failures = queryValue(def.promQl().failureQuery());
            return SloBudgetMath.snapshotFromCounts(backendId(), def,
                    successes, failures);
        } catch (final RestClientResponseException ex) {
            return SloRemoteFailureClassifier.classifyHttp(
                    backendId(), def, ex);
        } catch (final ResourceAccessException ex) {
            return SloRemoteFailureClassifier.classifyTransport(
                    backendId(), def, ex);
        } catch (final PromQlApiException ex) {
            return SloSnapshot.transientFailure(backendId(), def,
                    backendId() + ":api-error");
        } catch (final RuntimeException ex) {
            return SloSnapshot.transientFailure(backendId(), def,
                    backendId() + ":exception:" + ex.getClass().getSimpleName());
        }
    }

    private double queryValue(final String query) {
        final String body = this.restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path(PROMETHEUS_QUERY_PATH)
                        .queryParam("query", query)
                        .build())
                .retrieve()
                .body(String.class);
        return parseQueryResponse(body);
    }

    private double parseQueryResponse(final String body) {
        try {
            final JsonNode root = this.objectMapper.readTree(body);
            if (!STATUS_SUCCESS.equals(root.path("status").asText())) {
                throw new PromQlApiException();
            }
            final JsonNode data = root.path("data");
            final String resultType = data.path("resultType").asText();
            if ("scalar".equals(resultType)) {
                return valueFromPair(data.path("result"));
            }
            if (!"vector".equals(resultType)) {
                throw new PromQlApiException();
            }
            double sum = 0.0d;
            for (final JsonNode result : data.path("result")) {
                sum += valueFromPair(result.path("value"));
            }
            return sum;
        } catch (final PromQlApiException ex) {
            throw ex;
        } catch (final RuntimeException | java.io.IOException ex) {
            throw new PromQlApiException();
        }
    }

    private static double valueFromPair(final JsonNode pair) {
        if (!pair.isArray() || pair.size() < 2) {
            throw new PromQlApiException();
        }
        final double value = Double.parseDouble(pair.get(1).asText());
        if (Double.isNaN(value) || Double.isInfinite(value) || value < 0.0d) {
            throw new PromQlApiException();
        }
        return value;
    }

    private static final class PromQlApiException extends RuntimeException {
    }
}
