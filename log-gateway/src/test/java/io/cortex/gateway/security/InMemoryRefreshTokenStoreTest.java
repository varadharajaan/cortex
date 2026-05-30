package io.cortex.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link InMemoryRefreshTokenStore}: register / consume
 * semantics, single-use guarantee, and expiry handling.
 */
class InMemoryRefreshTokenStoreTest {

    /** Fixed instant used by every test for deterministic expiry checks. */
    private static final Instant NOW = Instant.parse("2026-05-30T10:00:00Z");

    /** Fixed clock pinned at {@link #NOW}. */
    private final Clock fixedClock = Clock.fixed(NOW, ZoneOffset.UTC);

    /** Store under test, bound to {@link #fixedClock}. */
    private final InMemoryRefreshTokenStore store = new InMemoryRefreshTokenStore(this.fixedClock);

    /** A registered, unexpired token can be consumed exactly once. */
    @Test
    void consumesRegisteredTokenExactlyOnce() {
        this.store.register("jti-1", "alice", NOW.plusSeconds(60));

        assertThat(this.store.consume("jti-1")).isTrue();
        assertThat(this.store.consume("jti-1")).isFalse();
    }

    /** An unknown token id is rejected. */
    @Test
    void rejectsUnknownTokenId() {
        assertThat(this.store.consume("missing")).isFalse();
    }

    /** An expired token id is removed and rejected. */
    @Test
    void rejectsExpiredTokenId() {
        this.store.register("jti-old", "bob", NOW.minusSeconds(1));

        assertThat(this.store.consume("jti-old")).isFalse();
        // Even with a future re-register, the original entry is gone.
        assertThat(this.store.consume("jti-old")).isFalse();
    }

    /** Distinct token ids are tracked independently. */
    @Test
    void tracksMultipleTokensIndependently() {
        this.store.register("a", "alice", NOW.plusSeconds(60));
        this.store.register("b", "bob", NOW.plusSeconds(60));

        assertThat(this.store.consume("a")).isTrue();
        assertThat(this.store.consume("b")).isTrue();
        assertThat(this.store.consume("a")).isFalse();
    }
}
