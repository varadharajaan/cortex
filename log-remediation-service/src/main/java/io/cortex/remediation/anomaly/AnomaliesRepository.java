package io.cortex.remediation.anomaly;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * JDBC repository for the {@code anomalies} read-model table (P9.3).
 *
 * <p>Uses explicit SQL because the P9.3a endpoint needs optional
 * time filters and a bounded limit. All request values are bound as
 * JDBC parameters.</p>
 */
@Repository
public class AnomaliesRepository {

    private static final String SELECT_COLUMNS = """
            id, tenant_id, event_id, severity, reason, ts, level, service,
            message, confidence, anomaly_type, remediation_key, received_at
            """;

    private final JdbcClient jdbcClient;

    /**
     * Constructor injection.
     *
     * @param jdbcClient Spring JDBC client
     */
    public AnomaliesRepository(final JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * Inserts a row if this tenant/event pair has not already been
     * recorded.
     *
     * @param record anomaly read-model row
     * @return {@code true} when inserted, {@code false} when absorbed
     *         as a duplicate
     */
    public boolean insertIfAbsent(final AnomalyRecord record) {
        final int rows = this.jdbcClient.sql("""
                INSERT INTO anomalies (
                    tenant_id, event_id, severity, reason, ts, level, service,
                    message, confidence, anomaly_type, remediation_key, received_at
                ) VALUES (
                    :tenantId, :eventId, :severity, :reason, :ts, :level, :service,
                    :message, :confidence, :anomalyType, :remediationKey, :receivedAt
                )
                ON CONFLICT (tenant_id, event_id) DO NOTHING
                """)
                .param("tenantId", record.tenantId())
                .param("eventId", record.eventId())
                .param("severity", record.severity())
                .param("reason", record.reason())
                .param("ts", Timestamp.from(record.ts()))
                .param("level", record.level())
                .param("service", record.service())
                .param("message", record.message())
                .param("confidence", record.confidence())
                .param("anomalyType", record.anomalyType())
                .param("remediationKey", record.remediationKey())
                .param("receivedAt", Timestamp.from(record.receivedAt()))
                .update();
        return rows > 0;
    }

    /**
     * Finds anomaly rows for one tenant ordered newest first.
     *
     * @param tenantId tenant scope
     * @param since    optional inclusive lower timestamp bound
     * @param until    optional inclusive upper timestamp bound
     * @param limit    bounded maximum number of rows
     * @return matching anomalies ordered by {@code ts DESC, id DESC}
     */
    public List<AnomalyRecord> findByTenant(final String tenantId,
                                            final Instant since,
                                            final Instant until,
                                            final int limit) {
        final StringBuilder sql = new StringBuilder()
                .append("SELECT ")
                .append(SELECT_COLUMNS)
                .append(" FROM anomalies WHERE tenant_id = :tenantId");
        final Map<String, Object> params = new LinkedHashMap<>();
        params.put("tenantId", tenantId);
        if (since != null) {
            sql.append(" AND ts >= :since");
            params.put("since", Timestamp.from(since));
        }
        if (until != null) {
            sql.append(" AND ts <= :until");
            params.put("until", Timestamp.from(until));
        }
        sql.append(" ORDER BY ts DESC, id DESC LIMIT :limit");
        params.put("limit", limit);
        return this.jdbcClient.sql(sql.toString())
                .params(params)
                .query(AnomaliesRepository::mapRow)
                .list();
    }

    private static AnomalyRecord mapRow(final ResultSet rs,
                                        final int rowNum) throws SQLException {
        return new AnomalyRecord(
                rs.getLong("id"),
                rs.getString("tenant_id"),
                rs.getString("event_id"),
                rs.getString("severity"),
                rs.getString("reason"),
                instant(rs, "ts"),
                rs.getString("level"),
                rs.getString("service"),
                rs.getString("message"),
                rs.getDouble("confidence"),
                rs.getString("anomaly_type"),
                rs.getString("remediation_key"),
                instant(rs, "received_at"));
    }

    private static Instant instant(final ResultSet rs,
                                   final String column) throws SQLException {
        final Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
