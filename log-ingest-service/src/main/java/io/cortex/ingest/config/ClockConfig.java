package io.cortex.ingest.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes a UTC {@link Clock} bean so time-dependent collaborators
 * (services, filters) can inject a clock instead of calling
 * {@link java.time.Instant#now()} statically. Lets tests pin the
 * clock without {@code Mockito.mockStatic}.
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    /** Default constructor used by Spring. */
    public ClockConfig() {
        // no state; Spring instantiates via reflection
    }

    /**
     * Returns a UTC clock for production injection. Tests override
     * this bean (typically via {@code @MockBean Clock}) to pin the
     * acceptance timestamp.
     *
     * @return UTC {@link Clock} instance
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
