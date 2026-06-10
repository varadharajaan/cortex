package io.cortex.monitoring.slo;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * Shared target-service discovery and Prometheus text scrape
 * helpers for source-aware SLO engines.
 */
final class PrometheusTargetScrapeSupport {

    private PrometheusTargetScrapeSupport() {
    }

    static ServiceInstance selectInstance(final DiscoveryClient client,
                                          final SloDefinition def) {
        final List<ServiceInstance> instances =
                client.getInstances(def.serviceId());
        if (instances == null || instances.isEmpty()) {
            return null;
        }
        return instances.get(0);
    }

    static URI buildUri(final ServiceInstance instance,
                        final String actuatorPath) {
        final String base = instance.getUri().toString();
        if (base.endsWith("/") && actuatorPath.startsWith("/")) {
            return URI.create(base.substring(0, base.length() - 1)
                    + actuatorPath);
        }
        if (!base.endsWith("/") && !actuatorPath.startsWith("/")) {
            return URI.create(base + "/" + actuatorPath);
        }
        return URI.create(base + actuatorPath);
    }

    static String scrape(final RestClient restClient, final URI uri) {
        return restClient.get()
                .uri(uri)
                .accept(MediaType.TEXT_PLAIN)
                .retrieve()
                .body(String.class);
    }

    static boolean requiredTagsMatch(
            final Map<String, String> requiredTags,
            final Map<String, String> sampleTags) {
        for (final Map.Entry<String, String> required
                : requiredTags.entrySet()) {
            if (!required.getValue().equals(sampleTags.get(required.getKey()))) {
                return false;
            }
        }
        return true;
    }
}
