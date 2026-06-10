package io.cortex.remediation.dedupe;

import io.cortex.remediation.parse.AnomalyEvent;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Redis SETNX idempotency guard for remediation actions.
 */
@Service
@ConditionalOnProperty(
        name = "cortex.remediation.dedupe.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class RemediationDedupeService {

    /** Prefix for every remediation dedupe key. */
    public static final String KEY_PREFIX = "cortex:remediation:dedupe:";

    private static final Logger LOG =
            LoggerFactory.getLogger(RemediationDedupeService.class);
    private static final String MARKER = "1";

    private final StringRedisTemplate template;
    private final Duration ttl;

    /**
     * Spring constructor.
     *
     * @param template Redis string template
     * @param ttl dedupe claim lifetime
     */
    public RemediationDedupeService(
            final StringRedisTemplate template,
            @Value("${cortex.remediation.dedupe.ttl:PT24H}") final Duration ttl) {
        this.template = template;
        this.ttl = ttl;
    }

    /**
     * Claim the anomaly for remediation.
     *
     * @param event parsed anomaly event
     * @return {@code true} when processing should continue
     */
    public boolean claim(final AnomalyEvent event) {
        if (event == null || isBlank(event.tenantId()) || isBlank(event.eventId())) {
            LOG.warn("remediation-dedupe missing-key fail-open=true");
            return true;
        }
        final String key = KEY_PREFIX + event.tenantId() + ":" + event.eventId();
        try {
            final Boolean inserted =
                    this.template.opsForValue().setIfAbsent(key, MARKER, this.ttl);
            return inserted == null || Boolean.TRUE.equals(inserted);
        } catch (DataAccessException ex) {
            LOG.warn("remediation-dedupe redis-unreachable tenant={} eventId={}"
                            + " fail-open=true",
                    event.tenantId(), event.eventId(), ex);
            return true;
        }
    }

    private static boolean isBlank(final String value) {
        return value == null || value.isBlank();
    }
}
