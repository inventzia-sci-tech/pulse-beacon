/*
 * SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
 * Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.
 *
 * This file is part of pulse-beacon.
 *
 * pulse-beacon is dual-licensed:
 *   - Under the GNU Affero General Public License v3.0 (see LICENSE-AGPL-3.0).
 *   - Under a commercial license (see LICENSE-COMMERCIAL.txt).
 *     Contact operations@inventzia.com.
 */
package com.inventzia.pulse.beacon.core.gateway.database;

import com.inventzia.pulse.data.schemas.marketdata.CdfBar;
import com.inventzia.pulse.data.schemas.platform.HeartBeat;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Generated DDL and value binding — the part where "the DDL was accepted" has to be distinguished from
 * "the value came back unchanged".
 */
class SqlDialectTest {

    private DataSource ds;
    private final SqlDialect dialect = new H2Dialect();

    @BeforeEach
    void setUp() {
        JdbcDataSource d = new JdbcDataSource();
        d.setURL("jdbc:h2:mem:ddl_" + UUID.randomUUID().toString().replace("-", "")
                 + ";DB_CLOSE_DELAY=-1");
        d.setUser("sa");
        ds = d;
    }

    @AfterEach
    void tearDown() throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP ALL OBJECTS");
        }
    }

    private SqlTableBinding barsBinding() {
        return SqlTableBinding.of(QualifiedTableName.of("bars"), StorageMapping.of(CdfBar.class));
    }

    // ------------------------------------------------------------------
    // The DDL an engine actually accepts
    // ------------------------------------------------------------------

    @Test
    void theGeneratedDdlIsAcceptedAndProducesTheDeclaredTypes() throws Exception {
        SqlTableBinding b = barsBinding();
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute(dialect.createTableStatement(b));

            var actual = TableValidator.describe(c, b.table());
            assertThat(actual.get("op").jdbcType())
                    .as("a decimal must be created as an exact decimal")
                    .isIn(java.sql.JDBCType.DECIMAL, java.sql.JDBCType.NUMERIC);
            assertThat(actual.get("timestamp").jdbcType())
                    .as("the routing time is BIGINT epoch millis")
                    .isEqualTo(java.sql.JDBCType.BIGINT);
            // ...and the table it created validates against the binding that generated it.
            assertThat(TableValidator.validateForSink(c, b).problems()).isEmpty();
        }
    }

    @Test
    void theRoutingTimeIsBigintNotATimestamp() {
        String ddl = dialect.createTableStatement(
                SqlTableBinding.of(QualifiedTableName.of("beats"), StorageMapping.of(HeartBeat.class)));
        assertThat(ddl).contains("\"beatTime\" BIGINT");
    }

    @Test
    void theIngestionIdIsUniqueBecauseTheRetryArgumentDependsOnIt() throws Exception {
        SqlTableBinding b = SqlTableBinding.of(QualifiedTableName.of("beats"),
                StorageMapping.of(HeartBeat.class));
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute(dialect.createTableStatement(b));
            String insert = "INSERT INTO " + dialect.qualify(b.table())
                    + " (" + dialect.quote("beatKey") + ", " + dialect.quote("beatTime") + ", "
                    + dialect.quote(IngestionId.COLUMN) + ") VALUES ('k', 1, 'run:1')";
            st.execute(insert);
            // Without this constraint a retry could duplicate instead of proving anything.
            assertThatThrownBy(() -> st.execute(insert)).isInstanceOf(SQLException.class)
                    .satisfies(e -> assertThat(dialect.isUniqueViolation((SQLException) e)).isTrue());
        }
    }

    @Test
    void anUncharacterisedEngineClaimsNoOptionalCapability() {
        // The safe direction to default in: plainer, not quietly wrong.
        SqlDialect generic = new GenericSqlDialect();
        assertThat(generic.supportsDerivedTimestamp()).isFalse();
        assertThat(generic.supportsReplaySnapshot()).isFalse();
        assertThat(new H2Dialect().supportsDerivedTimestamp()).isTrue();
    }

    @Test
    void identifiersAreQuotedSoAReservedWordCannotChangeTheStatement() {
        assertThat(dialect.quote("order")).isEqualTo("\"order\"");
        assertThat(dialect.quote("a\"b")).as("an embedded quote is escaped, not passed through")
                .isEqualTo("\"a\"\"b\"");
    }

    // ------------------------------------------------------------------
    // Values must survive the round trip
    // ------------------------------------------------------------------

    @Test
    void aDecimalSurvivesTheRoundTripExactly() throws Exception {
        // The guarantee the whole DECIMAL/DOUBLE distinction exists for.
        SqlTableBinding b = barsBinding();
        BigDecimal price = new BigDecimal("12345.678901234567");

        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute(dialect.createTableStatement(b));
        }
        CdfBar bar = bar(price);
        insert(b, bar);

        try (Connection c = ds.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT " + dialect.quote("op")
                     + " FROM " + dialect.qualify(b.table()))) {
            rs.next();
            assertThat(rs.getBigDecimal(1)).isEqualByComparingTo(price);
        }
    }

    @Test
    void aValueTooPreciseForItsColumnIsRefusedNotRounded() throws Exception {
        // Rounding here would produce a table that disagrees with the run that wrote it, silently.
        SqlTableBinding b = barsBinding();
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute(dialect.createTableStatement(b));
        }
        // 13 decimal places against a scale of 12.
        CdfBar tooPrecise = bar(new BigDecimal("1.0000000000001"));

        assertThatThrownBy(() -> insert(b, tooPrecise))
                .isInstanceOf(DatumRowBinder.ValueOutOfRangeException.class)
                .hasMessageContaining("would round");
    }

    @Test
    void trailingZerosAreNotTreatedAsLostPrecision() throws Exception {
        // 1.50 into a scale-12 column loses nothing, and refusing it would be pedantic nonsense.
        SqlTableBinding b = barsBinding();
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute(dialect.createTableStatement(b));
        }
        insert(b, bar(new BigDecimal("1.50")));      // must not throw
    }

    @Test
    void aSubMillisecondInstantIsRefusedNotTruncated() throws Exception {
        // Instant carries nanoseconds; the column holds milliseconds. Timestamp.from() would drop the
        // extra digits without a word - the same silent rounding a decimal is protected from.
        SqlTableBinding b = barsBinding();
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute(dialect.createTableStatement(b));
        }
        CdfBar tooPrecise = new CdfBar("SYM", 1_700_000_000_000L,
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, null,
                Instant.parse("2026-01-02T00:00:00.123456789Z"), null,
                LocalDate.of(2026, 1, 2), null, null, null);

        assertThatThrownBy(() -> insert(b, tooPrecise))
                .isInstanceOf(DatumRowBinder.ValueOutOfRangeException.class)
                .hasMessageContaining("sub-millisecond");
    }

    @Test
    void aWholeMillisecondInstantIsAccepted() throws Exception {
        // The control: millisecond precision is the contract, not an obstacle.
        SqlTableBinding b = barsBinding();
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute(dialect.createTableStatement(b));
        }
        insert(b, new CdfBar("SYM", 1_700_000_000_000L,
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, null,
                Instant.parse("2026-01-02T00:00:00.123Z"), null,
                LocalDate.of(2026, 1, 2), null, null, null));      // must not throw
    }

    @Test
    void aTimestampIsWrittenInUtcRegardlessOfTheJvmZone() throws Exception {
        SqlTableBinding b = barsBinding();
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute(dialect.createTableStatement(b));
        }
        Instant when = Instant.parse("2026-03-29T01:30:00Z");   // inside a European DST transition
        insert(b, new CdfBar("SYM", 1_700_000_000_000L,
                new BigDecimal("1"), new BigDecimal("1"), new BigDecimal("1"), new BigDecimal("1"),
                new BigDecimal("1"), null, when, null, LocalDate.of(2026, 3, 29), null, null, null));

        try (Connection c = ds.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT " + dialect.quote("datetime")
                     + " FROM " + dialect.qualify(b.table()))) {
            rs.next();
            assertThat(rs.getTimestamp(1,
                    java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))).toInstant())
                    .isEqualTo(when);
        }
    }

    private static CdfBar bar(BigDecimal op) {
        return new CdfBar("SYM", 1_700_000_000_000L, op, op, op, op, op, null,
                Instant.ofEpochMilli(1_700_000_000_000L), null, LocalDate.of(2026, 1, 2),
                null, null, null);
    }

    private void insert(SqlTableBinding b, CdfBar bar) throws SQLException {
        DatumRowBinder binder = new DatumRowBinder(b.mapping());
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(dialect.insertStatement(b))) {
            binder.bind(ps, bar, new IngestionId("run", bar.timestamp()));
            ps.executeUpdate();
        }
    }
}
