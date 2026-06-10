package io.cortex.remediation.dedupe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.cortex.remediation.parse.AnomalyEvent;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * Unit coverage for the Redis SETNX remediation idempotency guard.
 */
class RemediationDedupeServiceTest {

    private static final Duration TTL = Duration.ofHours(24);

    /** First SETNX winner must continue remediation. */
    @Test
    void firstClaimReturnsTrueAndUsesTenantEventKey() {
        final RedisFixture fixture = fixture();
        when(fixture.ops.setIfAbsent(
                eq(RemediationDedupeService.KEY_PREFIX + "tenant-a:evt-1"),
                eq("1"),
                eq(TTL))).thenReturn(Boolean.TRUE);
        final RemediationDedupeService service =
                new RemediationDedupeService(fixture.template, TTL);

        final boolean claimed = service.claim(event("tenant-a", "evt-1"));

        assertThat(claimed).isTrue();
        verify(fixture.ops).setIfAbsent(
                eq(RemediationDedupeService.KEY_PREFIX + "tenant-a:evt-1"),
                eq("1"),
                eq(TTL));
    }

    /** Duplicate SETNX loser must suppress downstream remediation. */
    @Test
    void duplicateClaimReturnsFalse() {
        final RedisFixture fixture = fixture();
        when(fixture.ops.setIfAbsent(
                eq(RemediationDedupeService.KEY_PREFIX + "tenant-a:evt-1"),
                eq("1"),
                eq(TTL))).thenReturn(Boolean.FALSE);
        final RemediationDedupeService service =
                new RemediationDedupeService(fixture.template, TTL);

        assertThat(service.claim(event("tenant-a", "evt-1"))).isFalse();
    }

    /** Null Redis result is treated as fail-open so a broker record is not lost. */
    @Test
    void nullRedisResultFailsOpen() {
        final RedisFixture fixture = fixture();
        when(fixture.ops.setIfAbsent(
                eq(RemediationDedupeService.KEY_PREFIX + "tenant-a:evt-1"),
                eq("1"),
                eq(TTL))).thenReturn(null);
        final RemediationDedupeService service =
                new RemediationDedupeService(fixture.template, TTL);

        assertThat(service.claim(event("tenant-a", "evt-1"))).isTrue();
    }

    /** Missing tenant/event id cannot produce a stable key, so dedupe fails open. */
    @Test
    void missingKeyFailsOpenWithoutCallingRedis() {
        final RedisFixture fixture = fixture();
        final RemediationDedupeService service =
                new RemediationDedupeService(fixture.template, TTL);

        assertThat(service.claim(event("", "evt-1"))).isTrue();
        assertThat(service.claim(event("tenant-a", " "))).isTrue();
        assertThat(service.claim(null)).isTrue();
        verify(fixture.template, never()).opsForValue();
    }

    /** Redis outages are fail-open because Kafka offset movement is more important. */
    @Test
    void redisFailureFailsOpen() {
        final RedisFixture fixture = fixture();
        when(fixture.ops.setIfAbsent(
                eq(RemediationDedupeService.KEY_PREFIX + "tenant-a:evt-1"),
                eq("1"),
                eq(TTL))).thenThrow(new DataAccessResourceFailureException("down"));
        final RemediationDedupeService service =
                new RemediationDedupeService(fixture.template, TTL);

        assertThat(service.claim(event("tenant-a", "evt-1"))).isTrue();
    }

    private static AnomalyEvent event(final String tenantId, final String eventId) {
        return new AnomalyEvent(eventId, tenantId, "HIGH", "reason",
                Instant.parse("2026-06-09T00:00:00Z"), "ERROR", "checkout",
                "boom", 0.9d, "BURST", "restart-service");
    }

    private static RedisFixture fixture() {
        final StringRedisTemplate template = Mockito.mock(StringRedisTemplate.class);
        final ValueOperations<String, String> ops = valueOperations();
        when(template.opsForValue()).thenReturn(ops);
        return new RedisFixture(template, ops);
    }

    @SuppressWarnings("unchecked")
    private static ValueOperations<String, String> valueOperations() {
        return Mockito.mock(ValueOperations.class);
    }

    private record RedisFixture(
            StringRedisTemplate template,
            ValueOperations<String, String> ops) {
    }
}
