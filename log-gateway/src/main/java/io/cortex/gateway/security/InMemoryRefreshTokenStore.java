package io.cortex.gateway.security;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * In-memory {@link RefreshTokenStore} (P3.1 placeholder, single-replica only).
 *
 * <p>P3.2 swaps this out for a Redis-backed implementation so revocation
 * works across pods and survives a restart. The contract is unchanged.</p>
 */
@Component
@RequiredArgsConstructor
public class InMemoryRefreshTokenStore implements RefreshTokenStore {

    /** Active refresh-token ids keyed by {@code jti}. */
    private final ConcurrentMap<String, Entry> active = new ConcurrentHashMap<>();

    /** Clock used for expiry comparisons; overridable in tests. */
    private final Clock clock;

    @Override
    public void register(final String jti, final String subject, final Instant expiresAt) {
        this.active.put(jti, new Entry(subject, expiresAt));
    }

    @Override
    public boolean consume(final String jti) {
        final Entry entry = this.active.remove(jti);
        if (entry == null) {
            return false;
        }
        return entry.expiresAt().isAfter(Instant.now(this.clock));
    }

    /**
     * Internal value tracking subject + expiry per refresh-token id.
     *
     * @param subject   principal the token was issued to
     * @param expiresAt wall-clock expiry
     */
    private record Entry(String subject, Instant expiresAt) {
    }
}
