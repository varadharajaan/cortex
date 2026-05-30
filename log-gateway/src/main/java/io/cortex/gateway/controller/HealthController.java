package io.cortex.gateway.controller;

import io.cortex.gateway.config.GatewayProperties;
import io.cortex.gateway.constants.ApiPaths;
import io.cortex.gateway.dto.response.HealthResponse;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lightweight liveness endpoint owned by the gateway.
 *
 * <p>Independent of {@code /actuator/health} so external load balancers
 * can probe without enabling actuator publicly. Explicitly
 * {@link PreAuthorize permitted} to satisfy rule 18.4.</p>
 */
@RestController
@RequestMapping(ApiPaths.HEALTH)
@RequiredArgsConstructor
public class HealthController {

    /** Typed gateway settings (service name, environment label). */
    private final GatewayProperties properties;

    /**
     * Returns a populated {@link HealthResponse}.
     *
     * @return HTTP 200 with a small JSON body
     */
    @GetMapping
    @PreAuthorize("permitAll()")
    public ResponseEntity<HealthResponse> health() {
        return ResponseEntity.ok(new HealthResponse(
                "UP",
                this.properties.service(),
                this.properties.environment(),
                OffsetDateTime.now()));
    }
}
