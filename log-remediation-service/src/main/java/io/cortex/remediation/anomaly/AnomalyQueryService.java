package io.cortex.remediation.anomaly;

import java.time.Instant;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * Query service for tenant-scoped anomaly reads (P9.3a).
 */
@Service
public class AnomalyQueryService {

    /** Default row limit when the caller omits {@code limit}. */
    static final int DEFAULT_LIMIT = 100;

    /** Maximum row limit accepted by the direct remediation API. */
    static final int MAX_LIMIT = 500;

    private final AnomaliesRepository repository;

    /**
     * Constructor injection.
     *
     * @param repository anomaly repository
     */
    public AnomalyQueryService(final AnomaliesRepository repository) {
        this.repository = repository;
    }

    /**
     * Lists anomalies for a single tenant.
     *
     * @param tenantId tenant scope; required
     * @param since    optional inclusive lower timestamp bound
     * @param until    optional inclusive upper timestamp bound
     * @param limit    optional row limit; defaulted and clamped
     * @return matching anomaly rows ordered newest first
     * @throws IllegalArgumentException when tenant id, time window, or
     *                                  limit validation fails
     */
    public List<AnomalyRecord> find(final String tenantId,
                                    final Instant since,
                                    final Instant until,
                                    final Integer limit) {
        if (StringUtils.isBlank(tenantId)) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (since != null && until != null && since.isAfter(until)) {
            throw new IllegalArgumentException("since must be <= until");
        }
        return this.repository.findByTenant(tenantId.trim(), since, until,
                normalizeLimit(limit));
    }

    private static int normalizeLimit(final Integer requested) {
        if (requested == null) {
            return DEFAULT_LIMIT;
        }
        if (requested <= 0) {
            throw new IllegalArgumentException("limit must be greater than zero");
        }
        return Math.min(requested, MAX_LIMIT);
    }
}
