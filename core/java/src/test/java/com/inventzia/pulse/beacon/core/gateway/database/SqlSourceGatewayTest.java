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

import com.inventzia.pulse.beacon.core.AbstractGateway;
import com.inventzia.pulse.beacon.core.GatewayStatus;
import com.inventzia.pulse.beacon.core.Topic;
import com.inventzia.pulse.data.datum.Datum;
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
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Replaying a table as a historical source.
 *
 * <p>The tests that matter are the reproducibility ones: a total order that survives repeated runs,
 * and a bound that holds even while the table is being appended to. Both are properties nothing in the
 * engine can check for itself — each run is internally consistent and deterministic, they simply read
 * different data.
 */
class SqlSourceGatewayTest {

    private static final long START = 1_000_000L;
    private static final long END   = 2_000_000L;

    private static final QualifiedTableName BEATS = QualifiedTableName.of("heartbeats");
    private static final Topic<HeartBeat> TOPIC = new Topic<>("beats", HeartBeat.class);

    private String     dbName;
    private DataSource ds;
    private final SqlDialect dialect = new H2Dialect();
    private SqlTableBinding binding;

    @BeforeEach
    void setUp() throws Exception {
        dbName = "src_" + UUID.randomUUID().toString().replace("-", "");
        JdbcDataSource d = new JdbcDataSource();
        d.setURL("jdbc:h2:mem:" + dbName + ";DB_CLOSE_DELAY=-1");
        d.setUser("sa");
        ds = d;

        // The ingestion id is the final tiebreak. Without it the tuple is only unique if the datum's
        // own fields happen never to repeat - and two heartbeats for one key in the same millisecond
        // are perfectly legal.
        binding = SqlTableBinding.of(BEATS, StorageMapping.of(HeartBeat.class))
                .withOrderingColumns(List.of("beatTime", "beatKey", IngestionId.COLUMN));

        TableRegistry registry = new TableRegistry(ds);
        registry.ensureRegistryTable();
        registry.beginProvisioning(binding, "test");
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute(dialect.createTableStatement(binding));
        }
        registry.markReady(BEATS);
    }

    @AfterEach
    void tearDown() throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP ALL OBJECTS");
        }
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private long nextIngestion = 0;

    private void insert(String key, long time) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(dialect.insertStatement(binding))) {
            new DatumRowBinder(binding.mapping())
                    .bind(ps, new HeartBeat(key, time), new IngestionId("run", nextIngestion++));
            ps.executeUpdate();
        }
    }

    /** Collects what the source published, in the order it arrived. */
    private static final class Collector extends AbstractGateway {
        final List<HeartBeat> seen = Collections.synchronizedList(new ArrayList<>());
        Runnable onFirst;
        Collector() { super("collector", START, END); setDriveClock(false); }
        @Override public void run() { }
        @Override public <Q extends Datum> void onEvent(Topic<Q> topic, Q payload) {
            if (seen.isEmpty() && onFirst != null) onFirst.run();
            seen.add((HeartBeat) payload);
        }
        @Override public <Q extends Datum> void publish(Topic<Q> topic, Q payload) { }
    }

    /**
     * Subscriber routing is per key and one-to-one, so a collector has to be registered for every key
     * it should receive - an empty list routes nothing at all.
     */
    private SqlSourceGateway<HeartBeat> source(int pageSize, Collector collector, String... keys) {
        SqlSourceGateway<HeartBeat> src = new SqlSourceGateway<>(
                "Source", ds, binding, dialect, TOPIC, List.of(), START, END, pageSize);
        src.registerSubscriber(collector, TOPIC, List.of(keys));
        return src;
    }

    private List<HeartBeat> replay(int pageSize, String... keys) throws Exception {
        Collector collector = new Collector();
        source(pageSize, collector, keys).run();
        return collector.seen;
    }

    /** Keys KEY-00 .. KEY-(n-1), for the equal-timestamp cases. */
    private static String[] numberedKeys(int n) {
        String[] keys = new String[n];
        for (int i = 0; i < n; i++) keys[i] = "KEY-" + String.format("%02d", i);
        return keys;
    }

    // ------------------------------------------------------------------
    // Reading a window
    // ------------------------------------------------------------------

    @Test
    void readsEveryRowInTheWindowInTimeOrder() throws Exception {
        for (long t = START + 500; t < START + 5_500; t += 500) {
            insert("BEAT", t);
        }
        List<HeartBeat> seen = replay(1_000, "BEAT");

        assertThat(seen).hasSize(10);
        assertThat(seen).extracting(HeartBeat::getDatumTime).isSorted();
        assertThat(seen.get(0).getDatumTime()).isEqualTo(START + 500);
    }

    @Test
    void rowsOutsideTheWindowAreNotRead() throws Exception {
        insert("BEAT", START - 1);     // before
        insert("BEAT", START + 100);   // inside
        insert("BEAT", END + 1);       // after

        assertThat(replay(1_000, "BEAT")).extracting(HeartBeat::getDatumTime)
                .containsExactly(START + 100);
    }

    @Test
    void anEmptyWindowIsNotAFailure() throws Exception {
        Collector collector = new Collector();
        SqlSourceGateway<HeartBeat> src = source(1_000, collector, "BEAT");
        src.run();

        assertThat(collector.seen).isEmpty();
        assertThat(src.terminalFailure()).as("nothing to read is not an error").isEmpty();
        assertThat(src.rowsInWindow()).isZero();
    }

    @Test
    void theKeyFilterSelectsOnlyTheRequestedKeys() throws Exception {
        insert("WANTED", START + 100);
        insert("OTHER",  START + 200);
        insert("WANTED", START + 300);

        Collector collector = new Collector();
        SqlSourceGateway<HeartBeat> src = new SqlSourceGateway<>(
                "Source", ds, binding, dialect, TOPIC, List.of("WANTED"), START, END, 1_000);
        src.registerSubscriber(collector, TOPIC, List.of("WANTED"));
        src.run();

        assertThat(collector.seen).extracting(HeartBeat::getDatumKey)
                .containsExactly("WANTED", "WANTED");
    }

    // ------------------------------------------------------------------
    // Keyset pagination
    // ------------------------------------------------------------------

    @Test
    void aTableLargerThanOnePageIsReadCompletelyAndInOrder() throws Exception {
        for (int i = 0; i < 250; i++) {
            insert("BEAT", START + i);
        }
        Collector collector = new Collector();
        SqlSourceGateway<HeartBeat> src = source(25, collector, "BEAT");
        src.run();

        assertThat(collector.seen).hasSize(250);
        assertThat(collector.seen).extracting(HeartBeat::getDatumTime).isSorted();
        assertThat(src.pagesRead()).as("it really did page").isGreaterThan(1);
    }

    @Test
    void aPageBoundaryFallingInsideARunOfEqualTimestampsLosesNothing() throws Exception {
        // The case OFFSET and a non-total order both get wrong: the cursor must resume exactly, even
        // when the leading ordering column repeats across the boundary.
        for (int i = 0; i < 20; i++) {
            insert("KEY-" + String.format("%02d", i), START + 500);   // all the same event time
        }
        Collector collector = new Collector();
        SqlSourceGateway<HeartBeat> src = source(3, collector, numberedKeys(20)); // boundaries mid-run
        src.run();

        assertThat(collector.seen).hasSize(20);
        assertThat(collector.seen).extracting(HeartBeat::getDatumKey).doesNotHaveDuplicates();
        assertThat(src.pagesRead()).isGreaterThan(5);
    }

    @Test
    void equalTimestampsReplayInTheSameOrderEveryRun() throws Exception {
        // The TimeMachine cannot repair a non-deterministic source; it preserves whatever order it was
        // given. So the order has to be the source's to guarantee.
        for (int i = 0; i < 30; i++) {
            insert("KEY-" + String.format("%02d", i), START + (i % 3) * 100);
        }
        String[] keys = numberedKeys(30);
        List<String> first  = replay(7, keys).stream().map(HeartBeat::getDatumKey).toList();
        List<String> second = replay(7, keys).stream().map(HeartBeat::getDatumKey).toList();
        List<String> third  = replay(1_000, keys).stream().map(HeartBeat::getDatumKey).toList();

        assertThat(second).isEqualTo(first);
        assertThat(third).as("the order must not depend on the page size either").isEqualTo(first);
    }

    @Test
    void aCursorThatDoesNotAdvanceIsRefusedRatherThanLoopingForever() throws Exception {
        // Found by mutation: changing the page comparison from > to >= does not fail a test, it hangs
        // the run - the same page is re-read forever, with no error and no progress. A replay that
        // never ends and never says why is far worse to diagnose than one that fails, so the source
        // checks that the cursor moved instead of assuming it.
        for (int i = 0; i < 10; i++) {
            insert("BEAT", START + i * 10);
        }
        SqlDialect broken = new H2Dialect() {
            @Override public String selectPageStatement(SqlTableBinding b, boolean withCursor) {
                // >= instead of >: the boundary row repeats, so the cursor never moves past it.
                return super.selectPageStatement(b, withCursor).replace(") > (", ") >= (");
            }
        };
        Collector collector = new Collector();
        SqlSourceGateway<HeartBeat> src = new SqlSourceGateway<>(
                "Source", ds, binding, broken, TOPIC, List.of(), START, END, 3);
        src.registerSubscriber(collector, TOPIC, List.of("BEAT"));
        src.setFailureIsFatal(true);
        src.run();

        assertThat(src.terminalFailure()).as("it must stop and say why, not spin").isPresent();
        assertThat(src.terminalFailure().get().cause())
                .isInstanceOf(SqlSourceGateway.NonAdvancingCursorException.class);
        assertThat(src.terminalFailure().get().cause().getMessage())
                .contains("did not advance").contains("beatTime");
    }

    // ------------------------------------------------------------------
    // Duplicate ordering values
    // ------------------------------------------------------------------

    @Test
    void eventsSharingAKeyAndAMillisecondAreAllReplayed() throws Exception {
        // Not a contrived case: one instrument can produce several ticks inside the same millisecond,
        // so the datum's own fields cannot be relied on to be unique. The ingestion id is what makes
        // the tuple total, and every row must come back.
        for (int i = 0; i < 3; i++) {
            insert("SAME", START + 100);
        }
        Collector collector = new Collector();
        SqlSourceGateway<HeartBeat> src = source(1, collector, "SAME");   // page size 1: every
        src.run();                                                        // boundary is a duplicate

        assertThat(collector.seen).as("all three rows, not just the first at that value").hasSize(3);
        assertThat(src.rowsFetched()).isEqualTo(3);
        assertThat(src.terminalFailure()).isEmpty();
    }

    @Test
    void aReplayThatSkippedRowsFailsInsteadOfReportingASubset() throws Exception {
        // Defence in depth for the same bug. If the tuple can repeat, keyset paging skips every row
        // after the first at a duplicated value - and does it silently, because the cursor still
        // advances and no page is ever re-read. The row count taken at startup is what catches it,
        // and it works even when validate() was never called.
        SqlTableBinding duplicable = SqlTableBinding.of(BEATS, StorageMapping.of(HeartBeat.class))
                .withOrderingColumns(List.of("beatTime", "beatKey"));
        for (int i = 0; i < 3; i++) {
            insert("SAME", START + 100);
        }
        Collector collector = new Collector();
        SqlSourceGateway<HeartBeat> src = new SqlSourceGateway<>(
                "Source", ds, duplicable, dialect, TOPIC, List.of(), START, END, 1);
        src.registerSubscriber(collector, TOPIC, List.of("SAME"));
        src.setFailureIsFatal(true);
        src.run();

        assertThat(src.terminalFailure()).as("a partial replay must never pass as a complete one")
                .isPresent();
        assertThat(src.terminalFailure().get().cause())
                .isInstanceOf(SqlSourceGateway.IncompleteReplayException.class);
        assertThat(src.terminalFailure().get().cause().getMessage())
                .contains("replayed 1 of 3 rows").contains("not unique");
    }

    @Test
    void validateRefusesATupleThatIsNotDeclaredUnique() throws Exception {
        SqlTableBinding duplicable = SqlTableBinding.of(BEATS, StorageMapping.of(HeartBeat.class))
                .withOrderingColumns(List.of("beatTime", "beatKey"));
        Collector collector = new Collector();
        SqlSourceGateway<HeartBeat> src = new SqlSourceGateway<>(
                "Source", ds, duplicable, dialect, TOPIC, List.of(), START, END, 10);
        src.registerSubscriber(collector, TOPIC, List.of("SAME"));

        assertThatThrownBy(src::validate)
                .isInstanceOf(BindingMismatchException.class)
                .hasMessageContaining("not covered by any declared primary key or unique index")
                .hasMessageContaining(IngestionId.COLUMN);
    }

    // ------------------------------------------------------------------
    // The upper bound
    // ------------------------------------------------------------------

    @Test
    void rowsAppendedDuringTheReplayAreNotSeen() throws Exception {
        // Without the bound, a live feed appending mid-replay would let later pages see rows earlier
        // ones did not, and two runs of the same replay would silently differ.
        for (int i = 0; i < 30; i++) {
            insert("BEAT", START + i * 10);
        }
        Collector collector = new Collector();
        collector.onFirst = () -> {
            try {
                for (int i = 0; i < 20; i++) {
                    insert("LATE", START + 5_000 + i);   // inside the window, after the bound
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        };
        SqlSourceGateway<HeartBeat> src = source(5, collector, "BEAT", "LATE");
        src.run();

        assertThat(collector.seen).hasSize(30);
        assertThat(collector.seen).extracting(HeartBeat::getDatumKey)
                .as("rows written after the bound was fixed must not appear").doesNotContain("LATE");
        assertThat(src.rowsInWindow()).isEqualTo(30);
    }

    @Test
    void theBoundAloneExcludesConcurrentAppendsWithNoSnapshotToHelp() throws Exception {
        // The test above passes even without the bound, because H2 replays under a snapshot that
        // already hides concurrent writes. The bound is the UNIVERSAL half of the story - the half
        // that has to work on an engine with no usable snapshot at all - so it has to be tested
        // with the snapshot switched off, or it is never really exercised.
        SqlDialect noSnapshot = new GenericSqlDialect();
        assertThat(noSnapshot.supportsReplaySnapshot()).isFalse();

        for (int i = 0; i < 30; i++) {
            insert("BEAT", START + i * 10);
        }
        Collector collector = new Collector();
        collector.onFirst = () -> {
            try {
                for (int i = 0; i < 20; i++) {
                    insert("LATE", START + 5_000 + i);   // inside the window, after the bound
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        };
        SqlSourceGateway<HeartBeat> src = new SqlSourceGateway<>(
                "Source", ds, binding, noSnapshot, TOPIC, List.of(), START, END, 5);
        src.registerSubscriber(collector, TOPIC, List.of("BEAT", "LATE"));
        src.run();

        assertThat(src.usedSnapshot()).as("nothing but the bound is protecting this read").isFalse();
        assertThat(collector.seen).hasSize(30);
        assertThat(collector.seen).extracting(HeartBeat::getDatumKey)
                .as("rows written after the bound was fixed must not appear").doesNotContain("LATE");
    }

    @Test
    void aRowWithANullInARequiredColumnIsRefusedNotTurnedIntoAHalfDatum() throws Exception {
        // The realistic shape: a pre-existing table adopted by assertBinding, whose column is nullable
        // at the database level while the schema says the field is required.
        QualifiedTableName bars = QualifiedTableName.of("bars");
        SqlTableBinding barsBinding = SqlTableBinding.of(bars, StorageMapping.of(CdfBar.class))
                .withOrderingColumns(List.of("timestamp", "symb"));
        TableRegistry registry = new TableRegistry(ds);
        registry.beginProvisioning(barsBinding, "test");
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute(dialect.createTableStatement(barsBinding));
            st.execute("ALTER TABLE " + dialect.qualify(bars) + " ALTER COLUMN "
                    + dialect.quote("op") + " SET NULL");
            st.execute("INSERT INTO " + dialect.qualify(bars) + " ("
                    + dialect.quote("symb") + ", " + dialect.quote("timestamp") + ", "
                    + dialect.quote("hi") + ", " + dialect.quote("lo") + ", "
                    + dialect.quote("cl") + ", " + dialect.quote("vlm") + ", "
                    + dialect.quote("datetime") + ", " + dialect.quote("date") + ", "
                    + dialect.quote(IngestionId.COLUMN) + ") VALUES "
                    + "('SYM', " + (START + 100) + ", 1, 1, 1, 1, TIMESTAMP '2026-01-02 00:00:00',"
                    + " DATE '2026-01-02', 'run:1')");
        }
        registry.markReady(bars);

        Topic<CdfBar> barTopic = new Topic<>("bars", CdfBar.class);
        AbstractGateway sink = new AbstractGateway("collector", START, END) {
            @Override public void run() { }
            @Override public <Q extends Datum> void onEvent(Topic<Q> t, Q pl) { }
            @Override public <Q extends Datum> void publish(Topic<Q> t, Q pl) { }
        };
        SqlSourceGateway<CdfBar> src = new SqlSourceGateway<>(
                "Source", ds, barsBinding, dialect, barTopic, List.of(), START, END, 10);
        src.registerSubscriber(sink, barTopic, List.of("SYM"));
        src.setFailureIsFatal(true);
        src.run();

        // A datum with a missing required field would fail somewhere much further downstream, where
        // nothing can say which row it came from.
        assertThat(src.terminalFailure()).isPresent();
        // Pinned to the reader's own check rather than to "something threw": the record's canonical
        // constructor would also reject this, but only with a message that names neither the column
        // nor the row it came from.
        assertThat(src.terminalFailure().get().cause())
                .isInstanceOf(DatumRowReader.CorruptRowException.class);
        assertThat(src.terminalFailure().get().cause().getMessage())
                .contains("op").contains("requires");
    }

    @Test
    void theBoundAndRowCountAreReportedForTheManifest() throws Exception {
        for (int i = 0; i < 5; i++) {
            insert("BEAT", START + i * 100);
        }
        Collector collector = new Collector();
        SqlSourceGateway<HeartBeat> src = source(1_000, collector, "BEAT");
        src.run();

        // A changed dataset is then detectable after the fact, even where it could not be prevented.
        assertThat(src.upperBound()).isEqualTo(START + 400);
        assertThat(src.rowsInWindow()).isEqualTo(5);
        assertThat(src.rowsRead()).isEqualTo(5);
        assertThat(src.usedSnapshot()).as("H2 is characterised for replay snapshots").isTrue();
    }

    // ------------------------------------------------------------------
    // Failure
    // ------------------------------------------------------------------

    @Test
    void aSourceThatFailsMidReplayReportsItAndStillReleasesTheBarrier() throws Exception {
        for (int i = 0; i < 5; i++) {
            insert("BEAT", START + i * 100);
        }
        Collector collector = new Collector();
        SqlSourceGateway<HeartBeat> src = source(1_000, collector, "BEAT");
        src.setFailureIsFatal(true);
        // Pull the table out from under it before it reads.
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP TABLE " + dialect.qualify(BEATS));
        }
        src.run();

        assertThat(src.terminalFailure()).as("the failure is reported, not just logged").isPresent();
        assertThat(src.terminalFailure().get().fatal()).isTrue();
        assertThat(src.terminalFailure().get().detail()).contains("failed reading");
        assertThat(src.status()).as("and it still disconnected, so the run cannot hang")
                .isEqualTo(GatewayStatus.STOPPED);
    }

    @Test
    void aCompletedReplayReportsNoFailure() throws Exception {
        // The control: end-of-table must not look like failure.
        insert("BEAT", START + 100);
        Collector collector = new Collector();
        SqlSourceGateway<HeartBeat> src = source(1_000, collector, "BEAT");
        src.run();

        assertThat(src.terminalFailure()).isEmpty();
        assertThat(src.status()).as("the same terminal status as the failing source")
                .isEqualTo(GatewayStatus.STOPPED);
    }

    // ------------------------------------------------------------------
    // Contract
    // ------------------------------------------------------------------

    @Test
    void aSourceDoesNotReceiveEvents() {
        assertThatThrownBy(() -> source(10, new Collector(), "BEAT").onEvent(TOPIC, new HeartBeat("k", 1)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aBindingWithoutAnOrderingTupleCannotBeASource() {
        SqlTableBinding unordered = SqlTableBinding.of(BEATS, StorageMapping.of(HeartBeat.class));
        assertThatThrownBy(() -> new SqlSourceGateway<>(
                "Source", ds, unordered, dialect, TOPIC, List.of(), START, END))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ordering tuple");
    }

    @Test
    void validateRefusesAnUnregisteredTable() throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("DELETE FROM " + TableRegistry.REGISTRY_TABLE);
        }
        assertThatThrownBy(() -> source(10, new Collector(), "BEAT").validate())
                .isInstanceOf(BindingMismatchException.class);
    }

    @Test
    void validateAcceptsAProvisionedTable() throws Exception {
        source(10, new Collector(), "BEAT").validate();    // must not throw
    }

    // ------------------------------------------------------------------
    // Round trip through the sink
    // ------------------------------------------------------------------

    @Test
    void whatTheSinkWroteIsWhatTheSourceReadsBack() throws Exception {
        // The end-to-end proof, and the one that would catch a mapping that is merely self-consistent.
        QualifiedTableName bars = QualifiedTableName.of("bars");
        SqlTableBinding barsBinding = SqlTableBinding.of(bars, StorageMapping.of(CdfBar.class))
                .withOrderingColumns(List.of("timestamp", "symb"));

        TableRegistry registry = new TableRegistry(ds);
        registry.beginProvisioning(barsBinding, "test");
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute(dialect.createTableStatement(barsBinding));
        }
        registry.markReady(bars);

        BigDecimal price = new BigDecimal("12345.678901234567");
        CdfBar written = new CdfBar("SYM", START + 100, price, price, price, price, price, null,
                Instant.ofEpochMilli(START + 100), 42L, LocalDate.of(2026, 1, 2), null, null, null);

        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(dialect.insertStatement(barsBinding))) {
            new DatumRowBinder(barsBinding.mapping())
                    .bind(ps, written, new IngestionId("run", 1));
            ps.executeUpdate();
        }

        Topic<CdfBar> barTopic = new Topic<>("bars", CdfBar.class);
        List<CdfBar> read = Collections.synchronizedList(new ArrayList<>());
        AbstractGateway sink = new AbstractGateway("collector", START, END) {
            @Override public void run() { }
            @Override public <Q extends Datum> void onEvent(Topic<Q> t, Q p) { read.add((CdfBar) p); }
            @Override public <Q extends Datum> void publish(Topic<Q> t, Q p) { }
        };
        SqlSourceGateway<CdfBar> src = new SqlSourceGateway<>(
                "Source", ds, barsBinding, dialect, barTopic, List.of(), START, END, 100);
        src.registerSubscriber(sink, barTopic, List.of("SYM"));
        src.run();

        assertThat(read).hasSize(1);
        CdfBar back = read.get(0);
        assertThat(back.symb()).isEqualTo("SYM");
        assertThat(back.timestamp()).isEqualTo(START + 100);
        assertThat(back.op()).as("a decimal must survive the round trip exactly")
                .isEqualByComparingTo(price);
        assertThat(back.datetime()).isEqualTo(written.datetime());
        assertThat(back.date()).isEqualTo(written.date());
        assertThat(back.count()).isEqualTo(42L);
        assertThat(back.vwap()).as("an absent optional stays absent").isNull();
    }
}
