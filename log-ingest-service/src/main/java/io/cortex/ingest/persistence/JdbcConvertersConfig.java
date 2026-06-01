package io.cortex.ingest.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.JDBCType;
import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.postgresql.util.PGobject;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.data.jdbc.core.mapping.JdbcValue;
import org.springframework.data.jdbc.repository.config.AbstractJdbcConfiguration;

/**
 * Wires the JSONB roundtrip for {@link RawLog#labels()} into the
 * Spring Data JDBC conversion service (P4.1 / ADR-0022 amendment /
 * LD61).
 *
 * <p>Extends {@link AbstractJdbcConfiguration} rather than just
 * exposing a {@code JdbcCustomConversions} bean. The latter form
 * loses the dialect-specific store conversions wired by Spring
 * Boot's {@code SpringBootJdbcConfiguration}, which in turn means
 * the Postgres dialect's {@code PGobject} machinery never binds
 * the {@code labels} column as JSONB and Hikari binds the raw map
 * as VARCHAR. Overriding {@link #userConverters()} preserves the
 * dialect store conversions AND prepends our two JSONB converters
 * to the conversion service (LD61).</p>
 *
 * <p>{@link MapToJsonbWritingConverter} packages the map into a
 * {@link PGobject} of type {@code jsonb}; pgjdbc auto-binds
 * {@code PGobject} with {@code Types.OTHER} so Postgres treats
 * the bind as JSONB rather than character varying.
 * {@link JsonbToMapReadingConverter} parses the {@code PGobject}
 * value back into a flat {@code Map<String, String>} on record
 * reads.</p>
 */
@Configuration
public class JdbcConvertersConfig extends AbstractJdbcConfiguration {

    /** Default constructor used by Spring. */
    public JdbcConvertersConfig() {
        // no state
    }

    /**
     * Prepends our two JSONB converters to the conversion service so
     * the {@code labels} column on {@link RawLog} roundtrips through
     * Postgres JSONB.
     *
     * @return immutable list of user converters
     */
    @Override
    protected List<?> userConverters() {
        return List.of(
                new MapToJsonbWritingConverter(),
                new JsonbToMapReadingConverter());
    }

    /**
     * Writes a {@code Map<String, String>} as a Postgres JSONB
     * value via {@link JdbcValue} so Spring Data JDBC binds the
     * parameter with {@link JDBCType#OTHER} instead of inferring
     * {@code VARCHAR} from the property's Java type (LD61).
     */
    @WritingConverter
    static final class MapToJsonbWritingConverter
            implements Converter<Map<String, String>, JdbcValue> {

        /** Reusable JSON encoder. */
        private static final ObjectMapper MAPPER = new ObjectMapper();

        /** Default constructor used by the converter registry. */
        MapToJsonbWritingConverter() {
            // no state
        }

        @Override
        public JdbcValue convert(final Map<String, String> source) {
            try {
                final PGobject obj = new PGobject();
                obj.setType("jsonb");
                obj.setValue(MAPPER.writeValueAsString(
                        source == null ? Collections.emptyMap() : source));
                return JdbcValue.of(obj, JDBCType.OTHER);
            } catch (SQLException | com.fasterxml.jackson.core.JsonProcessingException ex) {
                throw new IllegalArgumentException("failed to serialise labels as jsonb", ex);
            }
        }
    }

    /**
     * Reads a Postgres JSONB {@link PGobject} into a flat
     * {@code Map<String, String>}; safe against {@code null}
     * column values.
     */
    @ReadingConverter
    static final class JsonbToMapReadingConverter
            implements Converter<PGobject, Map<String, String>> {

        /** Reusable JSON decoder. */
        private static final ObjectMapper MAPPER = new ObjectMapper();

        /** Target type for Jackson deserialisation. */
        private static final TypeReference<LinkedHashMap<String, String>> MAP_TYPE =
                new TypeReference<>() { };

        /** Default constructor used by the converter registry. */
        JsonbToMapReadingConverter() {
            // no state
        }

        @Override
        public Map<String, String> convert(final PGobject source) {
            final String value = source.getValue();
            if (value == null || value.isBlank()) {
                return Collections.emptyMap();
            }
            try {
                return MAPPER.readValue(value, MAP_TYPE);
            } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
                throw new IllegalArgumentException("failed to deserialise labels jsonb", ex);
            }
        }
    }
}
