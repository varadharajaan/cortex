package io.cortex.ingest.service.impl;

import io.cortex.ingest.dto.request.IngestBatchRequest;
import io.cortex.ingest.dto.response.IngestAcceptedResponse;
import io.cortex.ingest.service.IngestService;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Service;

/**
 * P4.0 scaffold implementation of {@link IngestService}.
 *
 * <p>Returns a constant-shape {@link IngestAcceptedResponse}
 * carrying the inbound entry count and the server-side acceptance
 * timestamp. NO persistence, dedupe, masking, enrichment, or queue
 * publish happens here yet -- those land in P4.1..P4.4 respectively
 * (see the {@link IngestService} javadoc for the phase plan).</p>
 */
@Service
public class IngestServiceImpl implements IngestService {

    /** Clock used for the acceptance timestamp; injected so tests can pin it. */
    private final Clock clock;

    /**
     * Constructs the scaffold service implementation.
     *
     * @param clock clock used for the acceptance timestamp; must not be
     *              {@code null}
     */
    public IngestServiceImpl(final Clock clock) {
        this.clock = clock;
    }

    @Override
    public IngestAcceptedResponse acceptBatch(final IngestBatchRequest request) {
        return new IngestAcceptedResponse(
                request.entries().size(),
                OffsetDateTime.now(this.clock));
    }
}
