package io.cortex.gateway.config;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the Bucket4j + Lettuce + Redis stack used by the
 * {@link io.cortex.gateway.filter.RateLimitFilter} (B5, P3.2 / ADR-0017).
 *
 * <p>The whole stack is created only when
 * {@code cortex.gateway.rate-limit.enabled=true} so the test classpath
 * (which sets it to {@code false}) does not need a live Redis instance
 * to boot the application context. Beans are exposed in declaration
 * order so the {@link RedisClient} and {@link StatefulRedisConnection}
 * are torn down cleanly on context shutdown via their {@code close()}
 * methods (registered automatically by Spring's bean lifecycle).</p>
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "cortex.gateway.rate-limit", name = "enabled", havingValue = "true")
public class RateLimitConfig {

    /** Typed rate-limit configuration. */
    private final RateLimitProperties properties;

    /**
     * Single Lettuce {@link RedisClient} for the rate-limit store.
     * Spring calls {@link RedisClient#shutdown()} on context close
     * because the method is named {@code shutdown()}.
     *
     * @return a configured Lettuce client bound to {@link RateLimitProperties#redisUri()}
     */
    @Bean(destroyMethod = "shutdown")
    @SuppressFBWarnings(
            value = "CT_CONSTRUCTOR_THROW",
            justification = "Bean factory method; Spring discards the partially-built bean on failure.")
    public RedisClient rateLimitRedisClient() {
        final RedisURI uri = RedisURI.create(properties.redisUri());
        log.info("rate-limit: connecting Lettuce to redis host={} port={} db={}",
                uri.getHost(), uri.getPort(), uri.getDatabase());
        return RedisClient.create(uri);
    }

    /**
     * Long-lived connection used by the proxy manager. Lettuce
     * connections are thread-safe so a single instance is shared.
     *
     * @param client the Lettuce client bean
     * @return an open string-keyed, byte-valued Redis connection
     */
    @Bean(destroyMethod = "close")
    public StatefulRedisConnection<String, byte[]> rateLimitRedisConnection(final RedisClient client) {
        final RedisCodec<String, byte[]> codec = RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE);
        return client.connect(codec);
    }

    /**
     * Bucket4j {@link ProxyManager} bound to Lettuce. The CAS variant
     * uses Redis {@code GETEX}/{@code SET ... XX EX} optimistic locking
     * to atomically update the serialized bucket state, so per-key
     * token consumption stays consistent across gateway replicas
     * (rule B5.1; defended against benchmark sketch in ADR-0017).
     *
     * <p>Uses the deprecated {@code builderFor(StatefulRedisConnection)}
     * overload because Bucket4j 8.14 deprecated every {@code builderFor}
     * variant in favour of a {@code Bucket4jLettuce} helper that was
     * only introduced in 8.15+; 8.14 has no non-deprecated alternative.
     * Tracked for upgrade in {@code memory.md} (LD33).</p>
     *
     * @param connection the long-lived Lettuce connection bean
     * @return a configured Lettuce-based proxy manager keyed by string
     */
    @Bean
    @SuppressWarnings("deprecation")
    public ProxyManager<String> rateLimitProxyManager(
            final StatefulRedisConnection<String, byte[]> connection) {
        return LettuceBasedProxyManager.builderFor(connection).build();
    }

    /**
     * Bucket configuration for AUTHENTICATED traffic: one bandwidth limit
     * of {@code capacity} tokens that refills greedily over
     * {@code refillPeriod}.
     *
     * @return immutable bucket configuration applied to per-principal buckets
     */
    @Bean
    public BucketConfiguration authenticatedBucketConfiguration() {
        return BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(properties.capacity())
                        .refillGreedy(properties.capacity(), properties.refillPeriod())
                        .build())
                .build();
    }

    /**
     * Bucket configuration for ANONYMOUS traffic: a smaller bucket sized
     * to {@code anonymousCapacity} so unauthenticated endpoints (login,
     * health) cannot be flooded.
     *
     * @return immutable bucket configuration applied to per-IP buckets
     */
    @Bean
    public BucketConfiguration anonymousBucketConfiguration() {
        return BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(properties.anonymousCapacity())
                        .refillGreedy(properties.anonymousCapacity(), properties.refillPeriod())
                        .build())
                .build();
    }

    /**
     * Window over which buckets fully refill; exposed as a bean so the
     * filter can render an accurate {@code X-RateLimit-Reset} header.
     *
     * @return refill window duration
     */
    @Bean
    public Duration rateLimitRefillPeriod() {
        return properties.refillPeriod();
    }
}
