package io.cortex.ingest.dedupe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * Unit tests for {@link IdempotencyDedupeService}. Drives every
 * branch of {@link IdempotencyDedupeService#claim(String, String)}
 * with a mocked {@link StringRedisTemplate} so the JaCoCo 80% line +
 * 80% branch gate is met without a live Redis (the Testcontainers
 * Redis IT lives in {@code IdempotencyDedupeIT}, added in a later
 * commit of the P4.2 chain).
 */
class IdempotencyDedupeServiceTest {

    /** TTL used throughout; small value keeps the tests self-documenting. */
    private static final Duration TTL = Duration.ofMinutes(5);

    /** Default constructor used by JUnit. */
    IdempotencyDedupeServiceTest() {
        // no state; per-test wiring is local
    }

    /**
     * A fresh key returns {@code true} (newly claimed) and the
     * underlying SETNX is invoked exactly once with the configured
     * TTL and the canonical key shape.
     */
    @Test
    @SuppressWarnings("unchecked")
    void freshKeyIsClaimed() {
        final StringRedisTemplate template = mock(StringRedisTemplate.class);
        final ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(Boolean.TRUE);
        final IdempotencyDedupeService svc = new IdempotencyDedupeService(template, TTL);

        final boolean claimed = svc.claim("tenant-a", "idem-1");

        assertThat(claimed).isTrue();
        verify(ops).setIfAbsent("cortex:ingest:idem:tenant-a:idem-1", "1", TTL);
    }

    /**
     * A second claim against the same key returns {@code false}
     * (hot-path hit) so the caller short-circuits persistence.
     */
    @Test
    @SuppressWarnings("unchecked")
    void replayIsRejected() {
        final StringRedisTemplate template = mock(StringRedisTemplate.class);
        final ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(Boolean.FALSE);
        final IdempotencyDedupeService svc = new IdempotencyDedupeService(template, TTL);

        final boolean claimed = svc.claim("tenant-b", "idem-2");

        assertThat(claimed).isFalse();
    }

    /**
     * A {@code null} return from Lettuce (rare but allowed by the
     * Spring contract on pipeline / queueing connections) is
     * treated as not-claimed so the caller proceeds with persistence
     * and the cold-path UNIQUE constraint absorbs any actual
     * duplicate.
     */
    @Test
    @SuppressWarnings("unchecked")
    void nullSetIfAbsentReturnTreatedAsNotClaimed() {
        final StringRedisTemplate template = mock(StringRedisTemplate.class);
        final ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(null);
        final IdempotencyDedupeService svc = new IdempotencyDedupeService(template, TTL);

        final boolean claimed = svc.claim("tenant-c", "idem-3");

        assertThat(claimed).isFalse();
    }

    /**
     * Redis-side failure (subclasses of {@link
     * org.springframework.dao.DataAccessException}) is FAIL-OPEN: a
     * WARN is logged and {@code true} is returned so the cold-path
     * backstop still runs.
     */
    @Test
    @SuppressWarnings("unchecked")
    void redisFailureFailsOpen() {
        final StringRedisTemplate template = mock(StringRedisTemplate.class);
        final ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new QueryTimeoutException("redis down"));
        final IdempotencyDedupeService svc = new IdempotencyDedupeService(template, TTL);

        final boolean claimed = svc.claim("tenant-d", "idem-4");

        assertThat(claimed).isTrue();
    }
}
