package io.cortex.ingest.dedupe;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Hot-path dedupe for {@code POST /api/v1/ingest/batch} keyed on the
 * {@code Idempotency-Key} request header (D3 / plan.md row P4.2 /
 * spec Sec 5.3 "Idempotency via Redis").
 *
 * <p>A successful SETNX claims the per-tenant key for {@link #ttl},
 * and subsequent replays of the same batch within that window are
 * short-circuited by
 * {@link io.cortex.ingest.service.impl.IngestServiceImpl} without
 * touching Postgres. The cold-path
 * {@code UNIQUE (tenant_id, event_id)} constraint on {@code raw_logs}
 * remains the durable backstop for the case where Redis is
 * unreachable or the {@code Idempotency-Key} header is absent.</p>
 *
 * <p>Key shape: {@code cortex:ingest:idem:{tenantId}:{idempotencyKey}}.
 * Value is the opaque marker {@code "1"} (we do not cache the
 * response body today; the second call recomputes {@code receivedAt}
 * which is consistent with the existing cold-path replay
 * behaviour).</p>
 *
 * <p>The bean is gated by {@code cortex.ingest.dedupe.enabled}
 * (default {@code true}). When disabled, the bean is absent,
 * {@code IngestServiceImpl} sees {@link java.util.Optional#empty()}
 * and skips the hot path entirely so existing slice / IT tests can
 * boot without standing up a Redis container.</p>
 */
@Service
@ConditionalOnProperty(
        name = "cortex.ingest.dedupe.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class IdempotencyDedupeService {

    /** Logger; kept private static final per LOGGERS_ARE_PRIVATE_STATIC_FINAL. */
    private static final Logger LOG = LoggerFactory.getLogger(IdempotencyDedupeService.class);

    /** Prefix for every dedupe key written to Redis. */
    private static final String KEY_PREFIX = "cortex:ingest:idem:";

    /** Opaque marker stored as the value; presence is the only signal. */
    private static final String MARKER = "1";

    /** Spring Data Redis template; injected by the auto-configured Lettuce factory. */
    private final StringRedisTemplate template;

    /** TTL applied on every successful SETNX; mirrors D3 / spec Sec 5.3. */
    private final Duration ttl;

    /**
     * Constructs the dedupe service.
     *
     * @param template Spring Data Redis template; must not be
     *                 {@code null}
     * @param ttl      claim lifetime; defaults to {@code PT24H} per D3
     */
    public IdempotencyDedupeService(
            final StringRedisTemplate template,
            @Value("${cortex.ingest.dedupe.ttl:PT24H}") final Duration ttl) {
        this.template = template;
        this.ttl = ttl;
    }

    /**
     * Atomically claims the dedupe key for the supplied tenant and
     * {@code Idempotency-Key} header value.
     *
     * <p>Returns {@code true} when the key was newly written (the
     * caller MUST proceed with persistence) and {@code false} when
     * the key was already present (hot-path hit; caller MUST
     * short-circuit and return the same {@code receivedCount} as the
     * original call).</p>
     *
     * <p>If Redis is unreachable the call FAILS OPEN: a WARN is
     * logged and {@code true} is returned so the cold-path UNIQUE
     * backstop still gets a chance to absorb the duplicate. This
     * keeps ingest available when the cache tier is degraded.</p>
     *
     * @param tenantId       non-null, non-blank tenant id
     * @param idempotencyKey non-null, non-blank request header value
     * @return {@code true} if newly claimed; {@code false} if
     *         hot-path hit
     */
    public boolean claim(final String tenantId, final String idempotencyKey) {
        final String key = KEY_PREFIX + tenantId + ":" + idempotencyKey;
        try {
            final Boolean inserted =
                    this.template.opsForValue().setIfAbsent(key, MARKER, this.ttl);
            return Boolean.TRUE.equals(inserted);
        } catch (DataAccessException ex) {
            LOG.warn("ingest-dedupe redis-unreachable tenant={} key={} fail-open=true",
                    tenantId, idempotencyKey, ex);
            return true;
        }
    }
}
