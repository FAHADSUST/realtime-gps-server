package com.rls.gps.history.location;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Writes accepted fixes to MySQL.
 *
 * <p>Plain JDBC rather than JPA. This service does one write - append a batch - millions of times a
 * day; entities, dirty checking and a persistence context all cost something and buy nothing here.
 * The read side (C10) is a keyset query that would be hand-written anyway.
 */
@Repository
public class LocationHistoryRepository {

    /**
     * {@code ON DUPLICATE KEY UPDATE id = id} is a deliberate no-op: a redelivered batch re-inserts
     * rows that already exist and MySQL quietly ignores them. That is what makes an at-least-once
     * queue safe to consume, without a separate deduplication table.
     */
    private static final String INSERT = """
            INSERT INTO user_location
                (company_id, user_id, latitude, longitude, recorded_at, received_at, accuracy, speed, heading)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE id = id
            """;

    private final JdbcTemplate jdbc;

    public LocationHistoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Appends a batch.
     *
     * <p>Returns how many fixes were <em>submitted</em>, not how many rows were new. With
     * {@code rewriteBatchedStatements=true} - which is what turns this into one multi-row statement
     * instead of N round trips - MySQL reports {@code SUCCESS_NO_INFO} per row, so a per-row count
     * would be a guess dressed up as a number.
     */
    public int insertAll(List<LocationFix> fixes) {
        if (fixes.isEmpty()) {
            return 0;
        }

        jdbc.batchUpdate(INSERT, new BatchPreparedStatementSetter() {

            @Override
            public void setValues(PreparedStatement statement, int index) throws SQLException {
                LocationFix fix = fixes.get(index);
                statement.setString(1, fix.companyId());
                statement.setString(2, fix.userId());
                statement.setDouble(3, fix.latitude());
                statement.setDouble(4, fix.longitude());
                statement.setObject(5, utc(fix.recordedAt()));
                statement.setObject(6, utc(fix.receivedAt()));
                setNullableDouble(statement, 7, fix.accuracy());
                setNullableDouble(statement, 8, fix.speed());
                setNullableDouble(statement, 9, fix.heading());
            }

            @Override
            public int getBatchSize() {
                return fixes.size();
            }
        });

        return fixes.size();
    }

    /**
     * Converts explicitly in UTC rather than handing the driver an {@code Instant} and hoping.
     * Left to the JVM's default zone, the same fix would land at a different time depending on where
     * the service happened to be running.
     */
    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static void setNullableDouble(PreparedStatement statement, int index, Double value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.DOUBLE);
        } else {
            statement.setDouble(index, value);
        }
    }
}
