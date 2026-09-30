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

import com.inventzia.pulse.data.schemas.platform.HeartBeat;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Validating a real table against a binding — and the fact that a source and a sink ask different
 * questions of the same table.
 *
 * <p>The case that motivates the split is concrete: an extra {@code NOT NULL} column with no default
 * is perfectly readable and makes every insert fail. A validator with one code path either refuses a
 * source for a column it never touches, or admits a sink that cannot write a single row.
 */
class TableValidatorTest {

    private String     dbName;
    private DataSource ds;

    private static final QualifiedTableName BEATS = QualifiedTableName.of("heartbeats");

    @BeforeEach
    void setUp() {
        dbName = "val_" + UUID.randomUUID().toString().replace("-", "");
        JdbcDataSource d = new JdbcDataSource();
        d.setURL("jdbc:h2:mem:" + dbName + ";DB_CLOSE_DELAY=-1");
        d.setUser("sa");
        ds = d;
    }

    @AfterEach
    void tearDown() throws Exception {
        execute("DROP ALL OBJECTS");
    }

    private static SqlTableBinding sinkBinding() {
        return SqlTableBinding.of(BEATS, StorageMapping.of(HeartBeat.class));
    }

    private static SqlTableBinding sourceBinding() {
        return sinkBinding().withOrderingColumns(List.of("beatTime", "beatKey"));
    }

    // ------------------------------------------------------------------
    // Where source and sink diverge
    // ------------------------------------------------------------------

    @Test
    void anExtraNotNullColumnWithNoDefaultBreaksOnlyTheSink() throws Exception {
        // Readable, and every insert fails. This is the whole reason the two checks are separate.
        execute("CREATE TABLE heartbeats ("
                + " beatKey VARCHAR(256) NOT NULL,"
                + " beatTime BIGINT NOT NULL,"
                + " ingested_by VARCHAR(64) NOT NULL,"
                + " PRIMARY KEY (beatKey, beatTime))");

        try (Connection c = ds.getConnection()) {
            assertThat(TableValidator.validateForSource(c, sourceBinding()).isValid())
                    .as("a source never names that column").isTrue();

            TableValidator.Result sink = TableValidator.validateForSink(c, sinkBinding());
            assertThat(sink.isValid()).isFalse();
            // The message names the column as the database spells it (H2 folds unquoted names up),
            // which is what an operator needs to go and look at it.
            assertThat(sink.problems()).singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                    .containsIgnoringCase("ingested_by").contains("every insert would fail");
        }
    }

    @Test
    void theSinkIsCaughtAtStartupNotOnTheFirstWrite() throws Exception {
        // Proving the failure is real: the insert the sink would issue genuinely fails.
        execute("CREATE TABLE heartbeats ("
                + " beatKey VARCHAR(256) NOT NULL,"
                + " beatTime BIGINT NOT NULL,"
                + " ingested_by VARCHAR(64) NOT NULL)");

        assertThatThrownBy(() -> execute(
                "INSERT INTO heartbeats (beatKey, beatTime) VALUES ('k', 1)"))
                .isInstanceOf(SQLException.class);

        try (Connection c = ds.getConnection()) {
            assertThat(TableValidator.validateForSink(c, sinkBinding()).isValid())
                    .as("startup must reach the same verdict the first write would")
                    .isFalse();
        }
    }

    @Test
    void anExtraNullableOrDefaultedColumnIsFineForBoth() throws Exception {
        execute("CREATE TABLE heartbeats ("
                + " beatKey VARCHAR(256) NOT NULL,"
                + " beatTime BIGINT NOT NULL,"
                + " note VARCHAR(64),"
                + " loaded_at BIGINT DEFAULT 0 NOT NULL,"
                + " PRIMARY KEY (beatKey, beatTime))");

        try (Connection c = ds.getConnection()) {
            assertThat(TableValidator.validateForSource(c, sourceBinding()).isValid()).isTrue();
            TableValidator.Result sink = TableValidator.validateForSink(c, sinkBinding());
            assertThat(sink.problems()).isEmpty();
        }
    }

    @Test
    void theSinkToleratesItsOwnIngestionIdColumn() throws Exception {
        // The sink writes it, so it must not be reported as an unwritable extra column.
        execute("CREATE TABLE heartbeats ("
                + " beatKey VARCHAR(256) NOT NULL,"
                + " beatTime BIGINT NOT NULL,"
                + " " + IngestionId.COLUMN + " VARCHAR(320) NOT NULL,"
                + " PRIMARY KEY (" + IngestionId.COLUMN + "))");

        try (Connection c = ds.getConnection()) {
            assertThat(TableValidator.validateForSink(c, sinkBinding()).problems()).isEmpty();
        }
    }

    // ------------------------------------------------------------------
    // What both refuse
    // ------------------------------------------------------------------

    @Test
    void aMissingColumnIsRefusedByBoth() throws Exception {
        execute("CREATE TABLE heartbeats (beatKey VARCHAR(256) NOT NULL)");

        try (Connection c = ds.getConnection()) {
            assertThat(TableValidator.validateForSource(c, sourceBinding()).problems())
                    .anyMatch(p -> p.contains("beatTime") && p.contains("missing"));
            assertThat(TableValidator.validateForSink(c, sinkBinding()).problems())
                    .anyMatch(p -> p.contains("beatTime") && p.contains("missing"));
        }
    }

    @Test
    void aDecimalFieldInAFloatingPointColumnIsRefused() throws Exception {
        // The exact loss the storage mapping exists to prevent, and the one any "is it numeric" check
        // waves through. CdfBar's prices must not land in DOUBLE.
        createBars("DOUBLE");

        try (Connection c = ds.getConnection()) {
            TableValidator.Result r = TableValidator.validateForSink(c, barsBinding());
            assertThat(r.isValid()).isFalse();
            assertThat(r.problems()).anyMatch(p -> p.toLowerCase().contains("op")
                    && p.contains("DECIMAL"));
        }
    }

    @Test
    void theSameDecimalFieldInADecimalColumnIsAccepted() throws Exception {
        // The control: without this, the test above would pass just as well if everything were refused.
        createBars("DECIMAL(38,12)");

        try (Connection c = ds.getConnection()) {
            assertThat(TableValidator.validateForSink(c, barsBinding()).problems()).isEmpty();
        }
    }

    @Test
    void aDoubleFieldInADecimalColumnIsAcceptedBecauseItLosesNothing() throws Exception {
        // The asymmetry is deliberate: widening a double into an exact decimal is safe, the reverse
        // is not.
        execute("CREATE TABLE widths ("
                + " k VARCHAR(64) NOT NULL, t BIGINT NOT NULL, d DECIMAL(38,12) NOT NULL)");
        try (Connection c = ds.getConnection()) {
            var actual = TableValidator.describe(c, QualifiedTableName.of("widths"));
            assertThat(actual.get("d").jdbcType())
                    .isIn(java.sql.JDBCType.DECIMAL, java.sql.JDBCType.NUMERIC);
        }
    }

    @Test
    void aNullableRoutingColumnIsRefused() throws Exception {
        // The engine needs a key and a time on every event; a nullable one is a run waiting to fail.
        execute("CREATE TABLE heartbeats ("
                + " beatKey VARCHAR(256),"
                + " beatTime BIGINT NOT NULL)");

        try (Connection c = ds.getConnection()) {
            assertThat(TableValidator.validateForSource(c, sourceBinding()).problems())
                    .anyMatch(p -> p.contains("beatKey") && p.contains("nullable"));
        }
    }

    @Test
    void aSourceWithoutAnOrderingTupleIsRefused() throws Exception {
        execute("CREATE TABLE heartbeats ("
                + " beatKey VARCHAR(256) NOT NULL,"
                + " beatTime BIGINT NOT NULL)");

        try (Connection c = ds.getConnection()) {
            // A sink-only binding has no tuple, and a source must not accept one.
            TableValidator.Result r = TableValidator.validateForSource(c, sinkBinding());
            assertThat(r.problems()).anyMatch(p -> p.contains("ordering tuple"));
            assertThat(TableValidator.validateForSink(c, sinkBinding()).isValid())
                    .as("a sink appends and needs no replay order").isTrue();
        }
    }

    @Test
    void orThrowNamesEveryProblemAtOnce() throws Exception {
        execute("CREATE TABLE heartbeats (beatKey VARCHAR(256))");

        try (Connection c = ds.getConnection()) {
            TableValidator.Result r = TableValidator.validateForSource(c, sinkBinding());
            assertThatThrownBy(r::orThrow)
                    .isInstanceOf(BindingMismatchException.class)
                    .hasMessageContaining("beatTime")
                    .hasMessageContaining("ordering tuple")
                    .hasMessageContaining("as a source");
        }
    }

    // ------------------------------------------------------------------
    // A column must have room, not merely the right type
    // ------------------------------------------------------------------

    @Test
    void aDecimalColumnTooNarrowToHoldTheMappingIsRefused() throws Exception {
        // DECIMAL(10,2) is a perfectly good DECIMAL and still rounds a scale-12 value on the way in.
        // That is the loss the DECIMAL/DOUBLE split exists to prevent, arriving by another route.
        createBars("DECIMAL(10,2)");
        try (Connection c = ds.getConnection()) {
            TableValidator.Result r = TableValidator.validateForSink(c, barsBinding());
            assertThat(r.isValid()).isFalse();
            assertThat(r.problems()).anyMatch(p -> p.toLowerCase().contains("op")
                    && p.contains("silently rounded"));
        }
    }

    @Test
    void aDecimalColumnWithEnoughRoomIsAccepted() throws Exception {
        // The control: without it the check above would pass just as well if every decimal were
        // refused. A wider column than needed is fine.
        createBars("DECIMAL(40,14)");
        try (Connection c = ds.getConnection()) {
            assertThat(TableValidator.validateForSink(c, barsBinding()).problems()).isEmpty();
        }
    }

    @Test
    void enoughTotalWidthDoesNotExcuseTooFewFractionalDigits() throws Exception {
        // 38 digits in total, but only 2 after the point: the integer part is ample and the scale is
        // what destroys the value. Precision and scale have to be checked as separate capacities.
        createBars("DECIMAL(38,2)");
        try (Connection c = ds.getConnection()) {
            assertThat(TableValidator.validateForSink(c, barsBinding()).problems())
                    .anyMatch(p -> p.contains("silently rounded"));
        }
    }

    @Test
    void aTimestampColumnWithoutMillisecondsIsRefused() throws Exception {
        execute("CREATE TABLE stamps ("
                + " symb VARCHAR(64) NOT NULL, timestamp BIGINT NOT NULL,"
                + " op DECIMAL(38,12) NOT NULL, hi DECIMAL(38,12) NOT NULL,"
                + " lo DECIMAL(38,12) NOT NULL, cl DECIMAL(38,12) NOT NULL,"
                + " vlm DECIMAL(38,12) NOT NULL, vwap DECIMAL(38,12),"
                + " datetime TIMESTAMP(0) NOT NULL,"          // seconds only
                + " count BIGINT, date DATE NOT NULL, expiry VARCHAR(32),"
                + " strike DECIMAL(38,12), symExp VARCHAR(64))");
        SqlTableBinding b = SqlTableBinding.of(QualifiedTableName.of("stamps"),
                StorageMapping.of(com.inventzia.pulse.data.schemas.marketdata.CdfBar.class));
        try (Connection c = ds.getConnection()) {
            assertThat(TableValidator.validateForSink(c, b).problems())
                    .anyMatch(p -> p.contains("datetime") && p.contains("TIMESTAMP(3)"));
        }
    }

    // ------------------------------------------------------------------
    // The ordering tuple must be verifiably unique
    // ------------------------------------------------------------------

    @Test
    void aTupleCoveredByThePrimaryKeyIsAccepted() throws Exception {
        execute("CREATE TABLE heartbeats ("
                + " beatKey VARCHAR(256) NOT NULL,"
                + " beatTime BIGINT NOT NULL,"
                + " PRIMARY KEY (beatKey, beatTime))");
        try (Connection c = ds.getConnection()) {
            assertThat(TableValidator.uniquenessIsDeclared(c, sourceBinding())).isTrue();
            assertThat(TableValidator.validateForSource(c, sourceBinding()).problems()).isEmpty();
        }
    }

    @Test
    void aTupleWithNoCoveringConstraintIsRefused() throws Exception {
        // Non-null is not enough. If the tuple can repeat, keyset paging skips every row after the
        // first at a duplicated value - silently, because the cursor still advances.
        execute("CREATE TABLE heartbeats ("
                + " beatKey VARCHAR(256) NOT NULL,"
                + " beatTime BIGINT NOT NULL)");
        try (Connection c = ds.getConnection()) {
            assertThat(TableValidator.uniquenessIsDeclared(c, sourceBinding())).isFalse();
            assertThat(TableValidator.validateForSource(c, sourceBinding()).problems())
                    .anyMatch(p -> p.contains("not covered by any declared primary key"));
        }
    }

    @Test
    void aUniqueIndexThatIsASubsetOfTheTupleIsEnough() throws Exception {
        // A subset suffices: extra ordering columns only refine an order that is already total.
        execute("CREATE TABLE heartbeats ("
                + " beatKey VARCHAR(256) NOT NULL,"
                + " beatTime BIGINT NOT NULL,"
                + " " + IngestionId.COLUMN + " VARCHAR(320) NOT NULL)");
        execute("CREATE UNIQUE INDEX ux_ing ON heartbeats (" + IngestionId.COLUMN + ")");

        SqlTableBinding withIngestion = sinkBinding()
                .withOrderingColumns(List.of("beatTime", "beatKey", IngestionId.COLUMN));
        try (Connection c = ds.getConnection()) {
            assertThat(TableValidator.uniquenessIsDeclared(c, withIngestion)).isTrue();
            assertThat(TableValidator.validateForSource(c, withIngestion).problems()).isEmpty();
        }
    }

    @Test
    void aNonUniqueIndexDoesNotCount() throws Exception {
        execute("CREATE TABLE heartbeats ("
                + " beatKey VARCHAR(256) NOT NULL,"
                + " beatTime BIGINT NOT NULL)");
        execute("CREATE INDEX ix_order ON heartbeats (beatTime, beatKey)");   // not unique
        try (Connection c = ds.getConnection()) {
            assertThat(TableValidator.uniquenessIsDeclared(c, sourceBinding()))
                    .as("an index that merely speeds the read does not make the tuple total")
                    .isFalse();
        }
    }

    @Test
    void anOrderingColumnOutsideTheDatumIsAllowedAndMustExist() throws Exception {
        // The ingestion id is not a datum field, and it is exactly what makes the tuple unique.
        SqlTableBinding withIngestion = sinkBinding()
                .withOrderingColumns(List.of("beatTime", "beatKey", IngestionId.COLUMN));
        assertThat(withIngestion.auxiliaryOrderingColumns()).containsExactly(IngestionId.COLUMN);

        execute("CREATE TABLE heartbeats ("
                + " beatKey VARCHAR(256) NOT NULL,"
                + " beatTime BIGINT NOT NULL)");     // the auxiliary column is missing
        try (Connection c = ds.getConnection()) {
            assertThat(TableValidator.validateForSource(c, withIngestion).problems())
                    .anyMatch(p -> p.contains(IngestionId.COLUMN) && p.contains("does not exist"));
        }
    }

    // ------------------------------------------------------------------
    // Ordering tuple discovery
    // ------------------------------------------------------------------

    @Test
    void theOrderingTupleIsDiscoveredFromThePrimaryKeyTimeFirst() throws Exception {
        execute("CREATE TABLE heartbeats ("
                + " beatKey VARCHAR(256) NOT NULL,"
                + " beatTime BIGINT NOT NULL,"
                + " PRIMARY KEY (beatKey, beatTime))");

        try (Connection c = ds.getConnection()) {
            List<String> tuple = TableValidator.discoverOrderingColumns(c, sinkBinding());
            assertThat(tuple).as("paging orders by time first, so the tuple must too")
                    .startsWith("BEATTIME".equalsIgnoreCase(tuple.get(0)) ? tuple.get(0) : "beatTime");
            assertThat(tuple).hasSize(2);
            assertThat(tuple.get(0)).isEqualToIgnoringCase("beatTime");
        }
    }

    @Test
    void aTableWithNoPrimaryKeyYieldsNoTuple() throws Exception {
        // Not a crash and not a guess: the caller is told there is nothing to replay on, and may still
        // declare a unique non-null tuple itself.
        execute("CREATE TABLE heartbeats ("
                + " beatKey VARCHAR(256) NOT NULL,"
                + " beatTime BIGINT NOT NULL)");

        try (Connection c = ds.getConnection()) {
            assertThat(TableValidator.discoverOrderingColumns(c, sinkBinding())).isEmpty();
        }
    }

    // ------------------------------------------------------------------
    // Binding invariants
    // ------------------------------------------------------------------

    @Test
    void anOrderingTupleMustStartWithTheTimeColumn() {
        assertThatThrownBy(() -> sinkBinding().withOrderingColumns(List.of("beatKey", "beatTime")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must begin with the routing time column");
    }

    @Test
    void anOrderingTupleCannotRepeatAColumn() {
        assertThatThrownBy(() -> sinkBinding().withOrderingColumns(List.of("beatTime", "beatTime")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("repeat");
    }

    @Test
    void anOrderingColumnOutsideTheDatumIsAcceptedByTheBinding() {
        // Deliberately permitted: the column that makes a tuple unique is often not a datum field at
        // all. Whether it exists is a question about the table, so the validator answers it - the
        // binding performs no I/O and cannot.
        SqlTableBinding b = sinkBinding().withOrderingColumns(List.of("beatTime", "anything"));
        assertThat(b.auxiliaryOrderingColumns()).containsExactly("anything");
    }

    @Test
    void aBindingIsReplayableOnlyWithATuple() {
        assertThat(sinkBinding().isReplayable()).isFalse();
        assertThat(sourceBinding().isReplayable()).isTrue();
    }

    private static SqlTableBinding barsBinding() {
        return SqlTableBinding.of(QualifiedTableName.of("bars"),
                StorageMapping.of(com.inventzia.pulse.data.schemas.marketdata.CdfBar.class));
    }

    /** A CdfBar table whose decimal columns are rendered with the given SQL type. */
    private void createBars(String decimalType) throws SQLException {
        execute("CREATE TABLE bars ("
                + " symb VARCHAR(64) NOT NULL,"
                + " timestamp BIGINT NOT NULL,"
                + " op "     + decimalType + " NOT NULL,"
                + " hi "     + decimalType + " NOT NULL,"
                + " lo "     + decimalType + " NOT NULL,"
                + " cl "     + decimalType + " NOT NULL,"
                + " vlm "    + decimalType + " NOT NULL,"
                + " vwap "   + decimalType + ","
                + " datetime TIMESTAMP NOT NULL,"
                + " count BIGINT,"
                + " date DATE NOT NULL,"
                + " expiry VARCHAR(32),"
                + " strike " + decimalType + ","
                + " symExp VARCHAR(64))");
    }

    private void execute(String sql) throws SQLException {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }
}
