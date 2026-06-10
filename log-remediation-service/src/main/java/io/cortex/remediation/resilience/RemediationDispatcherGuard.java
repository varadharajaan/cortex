package io.cortex.remediation.resilience;

import io.cortex.remediation.dispatch.DispatchResult;
import io.cortex.remediation.dispatch.RemediationDispatcher;
import io.cortex.remediation.parse.AnomalyEvent;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Programmatic Resilience4j guard for dispatcher calls.
 */
@Service
public class RemediationDispatcherGuard {

    private final boolean enabled;
    private final CircuitBreakerRegistry circuitBreakers;
    private final RetryRegistry retries;
    private final Map<String, CircuitBreaker> circuitCache = new ConcurrentHashMap<>();
    private final Map<String, Retry> retryCache = new ConcurrentHashMap<>();

    /**
     * Spring constructor.
     *
     * @param enabled enable flag
     * @param failureRateThreshold circuit failure-rate threshold
     * @param slidingWindowSize circuit sliding window size
     * @param minimumNumberOfCalls circuit minimum calls
     * @param waitDurationInOpenState open-state wait duration
     * @param permittedCallsInHalfOpen half-open permitted calls
     * @param retryMaxAttempts retry max attempts
     * @param retryWaitDuration retry wait duration
     */
    @SuppressWarnings("checkstyle:parameternumber")
    public RemediationDispatcherGuard(
            @Value("${cortex.remediation.resilience.enabled:true}") final boolean enabled,
            @Value("${cortex.remediation.resilience.circuit-breaker.failure-rate-threshold:50}")
            final float failureRateThreshold,
            @Value("${cortex.remediation.resilience.circuit-breaker.sliding-window-size:10}")
            final int slidingWindowSize,
            @Value("${cortex.remediation.resilience.circuit-breaker.minimum-number-of-calls:5}")
            final int minimumNumberOfCalls,
            @Value("${cortex.remediation.resilience.circuit-breaker.wait-duration-in-open-state:PT30S}")
            final Duration waitDurationInOpenState,
            @Value("${cortex.remediation.resilience.circuit-breaker.permitted-calls-in-half-open-state:2}")
            final int permittedCallsInHalfOpen,
            @Value("${cortex.remediation.resilience.retry.max-attempts:2}")
            final int retryMaxAttempts,
            @Value("${cortex.remediation.resilience.retry.wait-duration:PT0.1S}")
            final Duration retryWaitDuration) {
        this.enabled = enabled;
        this.circuitBreakers = circuitRegistry(failureRateThreshold, slidingWindowSize,
                minimumNumberOfCalls, waitDurationInOpenState,
                permittedCallsInHalfOpen);
        this.retries = retryRegistry(retryMaxAttempts, retryWaitDuration);
    }

    /**
     * Dispatch through retry and circuit-breaker guards.
     *
     * @param dispatcher active dispatcher
     * @param event anomaly event
     * @return dispatch result
     */
    public DispatchResult dispatch(final RemediationDispatcher dispatcher,
                                   final AnomalyEvent event) {
        if (dispatcher == null) {
            return DispatchResult.skipped("dispatcher:null");
        }
        final String channel = dispatcher.channelId();
        if (!this.enabled) {
            return dispatcher.dispatch(event);
        }
        final Supplier<DispatchResult> retried =
                Retry.decorateSupplier(retry(channel), () -> dispatcher.dispatch(event));
        try {
            return circuitBreaker(channel).executeSupplier(retried);
        } catch (CallNotPermittedException ex) {
            return DispatchResult.transientFailure(channel, channel + ":circuit-open");
        } catch (RuntimeException ex) {
            return DispatchResult.transientFailure(channel, channel + ":exception");
        }
    }

    private CircuitBreaker circuitBreaker(final String channel) {
        return this.circuitCache.computeIfAbsent(channel,
                key -> this.circuitBreakers.circuitBreaker("remediation-" + key));
    }

    private Retry retry(final String channel) {
        return this.retryCache.computeIfAbsent(channel,
                key -> this.retries.retry("remediation-" + key));
    }

    private static CircuitBreakerRegistry circuitRegistry(
            final float failureRateThreshold,
            final int slidingWindowSize,
            final int minimumNumberOfCalls,
            final Duration waitDurationInOpenState,
            final int permittedCallsInHalfOpen) {
        final CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(failureRateThreshold)
                .slidingWindowSize(slidingWindowSize)
                .minimumNumberOfCalls(minimumNumberOfCalls)
                .waitDurationInOpenState(waitDurationInOpenState)
                .permittedNumberOfCallsInHalfOpenState(permittedCallsInHalfOpen)
                .recordResult(RemediationDispatcherGuard::isTransientFailure)
                .build();
        return CircuitBreakerRegistry.of(config);
    }

    private static RetryRegistry retryRegistry(final int maxAttempts,
                                               final Duration waitDuration) {
        final RetryConfig config = RetryConfig.custom()
                .maxAttempts(maxAttempts)
                .waitDuration(waitDuration)
                .retryOnResult(RemediationDispatcherGuard::isTransientFailure)
                .retryOnException(ex -> true)
                .build();
        return RetryRegistry.of(config);
    }

    private static boolean isTransientFailure(final Object result) {
        return result instanceof DispatchResult dispatchResult
                && DispatchResult.OUTCOME_TRANSIENT_FAILURE.equals(dispatchResult.outcome());
    }
}
