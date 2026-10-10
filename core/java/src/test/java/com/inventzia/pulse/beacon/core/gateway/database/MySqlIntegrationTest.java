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
import com.inventzia.pulse.beacon.core.Topic;
import com.inventzia.pulse.data.datum.Datum;
import com.inventzia.pulse.data.schemas.marketdata.CdfBar;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
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
 * The SQL gateways against a <b>real MySQL server</b>.
 *
 * <p>This is the test the design doc asks for. Everything else runs against H2 — including H2 in MySQL
 * compatibility mode — and an embedded engine can accept identical DDL while behaving differently. Only
 * a real server settles whether the guarantees hold, and every finding below came from one rejecting
 * something H2 had happily accepted:
 *
 * <ul>
 *   <li>a managed MySQL with {@code sql_require_primary_key=ON} refuses a table with only a UNIQUE
 *       constraint, which is why the ingestion id is the primary key;</li>
 *   <li>an index key is capped at 3072 bytes, and under {@code utf8mb4} a few wide text columns exceed
 *       it, which is why the default text width is 255 and why a refused ordering index warns rather
 *       than fails;</li>
 *   <li>Connector/J reports {@code DECIMAL_DIGITS} as {@code null} for {@code DATETIME}, which is why
 *       the validator distinguishes "unreported" from "zero";</li>
 *   <li>MySQL reports a not-null violation with the same SQLSTATE as a duplicate, which is why a
 *       duplicate is identified by vendor error code.</li>
 * </ul>
 *
 * <p><b>It fails rather than skips when it cannot connect.</b> A test that silently passes because the
 * database was missing would make the whole exercise worthless — the point is a standing guarantee, not
 * an occasional one. It is excluded from the default build (see the {@code mysql-it} profile) so an
 * ordinary {@code mvn test} needs no server; CI runs it with one.
 *
 * <pre>
 *   mvn -Pmysql-it test -Dpulse.it.mysql.url=jdbc:mysql://127.0.0.1:3306/pulse \
 *                       -Dpulse.it.mysql.user=root -Dpulse.it.mysql.password=secret
 * </pre>
 */
class MySqlIntegrationTest {

    private static final long START = 1_000_000L;
    private static final long END   = 2_000_000L;

    private static final Topic<CdfBar> TOPIC = new Topic<>("bars", CdfBar.class);

    /** Values a 64-bit float cannot hold: the whole point of DECIMAL being distinct from DOUBLE. */
    private static final List<String> HOSTILE = List.of(
            "123456789012345.678901234567",
            "99999999999999999999.123456789012",
            "123456789.123456789123",
            "0.000000000001");

    private final SqlDialect dialect = new MySqlDialect();
    private DataSource ds;
    private QualifiedTableName table;
    private SqlTableBinding binding;

    @BeforeEach
    void setUp() throws Exception {
        String url  = required("pulse.it.mysql.url", "PULSE_IT_MYSQL_URL");
        String user = property("pulse.it.mysql.user", "PULSE_IT_MYSQL_USER", "root");
        String pass = property("pulse.it.mysql.password", "PULSE_IT_MYSQL_PASSWORD", "");

        ds = dataSource(url, user, pass);
        // Fail loudly here rather than let every test fail obscurely further down.
        try (Connection c = ds.getConnection()) {
            assertThat(c.getMetaData().getDatabaseProductName()).containsIgnoringCase("mysql");
        } catch (SQLException e) {
            throw new AssertionError("cannot reach the MySQL server this test requires: "
                    + e.getMessage() + "  (url without credentials: " + redact(url) + ")", e);
        }

        // A fresh table per test, so a failure cannot leak into the next one.
        table   = QualifiedTableName.of("pulse_it_" + UUID.randomUUID().toString().replace("-", ""));
        binding = SqlTableBinding.of(table, StorageMapping.of(CdfBar.class))
                .withOrderingColumns(List.of("timestamp", "symb", IngestionId.COLUMN));

        TableRegistry registry = new TableRegistry(ds);
        registry.ensureRegistryTable();
        registry.beginProvisioning(binding, "integration-test");
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute(dialect.createTableStatement(binding));
            st.execute(dialect.createOrderingIndexStatement(binding));
            st.execute(dialect.createNaturalKeyConstraintStatement(binding));
        }
        registry.markReady(table);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (ds == null || table == null) return;
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS " + dialect.qualify(table));
            st.execute("DELETE FROM " + TableRegistry.REGISTRY_TABLE
                    + " WHERE table_name = '" + table.table() + "'");
        }
    }

    // ------------------------------------------------------------------
    // What only a real server proves
    // ------------------------------------------------------------------

    @Test
    void theGeneratedDdlIsAcceptedAndValidatesForBothRoles() throws Exception {
        // setUp already ran the DDL; if sql_require_primary_key is ON this is where it would have
        // failed. Validation must then accept the table this dialect itself produced.
        try (Connection c = ds.getConnection()) {
            assertThat(TableValidator.validateForSink(c, binding, dialect).problems()).isEmpty();
            assertThat(TableValidator.validateForSource(c, binding, dialect).problems()).isEmpty();
            assertThat(TableValidator.uniquenessIsDeclared(c, binding)).isTrue();
            assertThat(TableValidator.naturalKeyIsUnique(c, binding)).isTrue();
        }
    }

    @Test
    void theIngestionIdIsThePrimaryKeyOnTheRealTable() throws Exception {
        // Not merely present in the DDL text: actually the primary key as the server reports it. A
        // managed MySQL with sql_require_primary_key=ON depends on this.
        try (Connection c = ds.getConnection();
             ResultSet rs = c.getMetaData().getPrimaryKeys(null, null, table.table())) {
            List<String> pk = new ArrayList<>();
            while (rs.next()) pk.add(rs.getString("COLUMN_NAME"));
            assertThat(pk).containsExactly(IngestionId.COLUMN);
        }
    }

    @Test
    void decimalsAndTimestampsSurviveTheRoundTripExactly() throws Exception {
        List<CdfBar> written = new ArrayList<>();
        long t = START + 100;
        for (String price : HOSTILE) {
            written.add(bar(price, t));
            t += 100;
        }
        SqlSinkGateway sink = sink("run-1", WriteMode.UPSERT_ON_NATURAL_KEY);
        sink.validate();
        load(sink, written);
        assertThat(sink.counts().committed()).isEqualTo(written.size());
        assertThat(sink.counts().isFinal()).isTrue();

        List<CdfBar> read = replay(2);
        assertThat(read).hasSize(written.size());
        for (int i = 0; i < written.size(); i++) {
            CdfBar w = written.get(i), r = read.get(i);
            assertThat(r.op()).as("decimal %s", w.op()).isEqualByComparingTo(w.op());
            // DATETIME(3), not TIMESTAMP: TIMESTAMP would be converted via the session time zone and
            // could not represent anything outside 1970-2038.
            assertThat(r.datetime()).as("exact UTC instant").isEqualTo(w.datetime());
            assertThat(r.date()).isEqualTo(w.date());
            assertThat(r.count()).isEqualTo(w.count());
        }
    }

    @Test
    void theDatetimeColumnKeepsMilliseconds() throws Exception {
        // Connector/J reports DECIMAL_DIGITS as null here, which once made the validator reject this
        // very column for "lacking" milliseconds. The server is the authority on what it created.
        try (Connection c = ds.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SHOW CREATE TABLE " + dialect.qualify(table))) {
            rs.next();
            assertThat(rs.getString(2)).contains("datetime(3)").doesNotContain("`datetime` timestamp");
        }
    }

    @Test
    void theProvisionedTableIsInnoDb() throws Exception {
        // The engine the server actually gave us, not what the DDL asked for. default_storage_engine
        // is a server setting, so this is the only way to know.
        //
        // The refusal half - that a non-InnoDB table is rejected - is a unit test instead: a managed
        // MySQL may disable MyISAM outright ("Storage engine MyISAM is disabled"), as Aiven does, so
        // the refusal cannot be provoked on every server this suite might run against.
        try (Connection c = ds.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT engine FROM information_schema.tables"
                     + " WHERE table_schema = DATABASE() AND table_name = '" + table.table() + "'")) {
            rs.next();
            assertThat(rs.getString(1)).isEqualToIgnoringCase("InnoDB");
        }
        // ...and the check verifies it: no problem AND nothing left unverified, which are different
        // claims. The second is the one that used to be silently true of an unchecked table.
        try (Connection c = ds.getConnection()) {
            SqlDialect.StorageEngineCheck check = dialect.storageEngineCheck(c, table);
            assertThat(check.problem()).isNull();
            assertThat(check.couldNotVerify()).as("it was actually verified").isNull();
        }

        // The table named by catalog rather than left to the connection's default, which is how
        // Connector/J reports a MySQL database.
        try (Connection c = ds.getConnection()) {
            QualifiedTableName byCatalog =
                    new QualifiedTableName(c.getCatalog(), null, table.table());
            SqlDialect.StorageEngineCheck check = dialect.storageEngineCheck(c, byCatalog);
            assertThat(check.problem()).isNull();
            assertThat(check.couldNotVerify())
                    .as("naming the database explicitly must still find the table").isNull();
        }
    }

    @Test
    void reloadingTheSameInputDoesNotDuplicateIt() throws Exception {
        List<CdfBar> bars = List.of(bar("1.5", START + 100), bar("2.5", START + 200));
        load(sink("run-1", WriteMode.UPSERT_ON_NATURAL_KEY), bars);
        load(sink("run-2", WriteMode.UPSERT_ON_NATURAL_KEY), bars);
        load(sink("run-3", WriteMode.UPSERT_ON_NATURAL_KEY), bars);

        assertThat(rowCount()).as("the same bars, however many times they are loaded").isEqualTo(2);
    }

    @Test
    void aDuplicateIsDistinguishedFromOtherIntegrityFailures() throws Exception {
        // MySQL gives a not-null violation the same SQLSTATE (23000) as a duplicate, so only the
        // vendor error code tells them apart. Getting this wrong is how an unrelated error reads as
        // proof that an earlier attempt committed.
        load(sink("run-1", WriteMode.APPEND), List.of(bar("1.5", START + 100)));

        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO " + dialect.qualify(table) + " ("
                    + dialect.quote("symb") + ", " + dialect.quote("timestamp") + ", "
                    + dialect.quote("op") + ", " + dialect.quote("hi") + ", "
                    + dialect.quote("lo") + ", " + dialect.quote("cl") + ", "
                    + dialect.quote("vlm") + ", " + dialect.quote("datetime") + ", "
                    + dialect.quote("date") + ", " + dialect.quote(IngestionId.COLUMN)
                    + ") VALUES ('CL', " + (START + 100) + ", 1, 1, 1, 1, 1,"
                    + " '2026-01-02 00:00:00.000', '2026-01-02', 'run-1:0')");
            org.assertj.core.api.Assertions.fail("the duplicate ingestion id should have been refused");
        } catch (SQLException e) {
            assertThat(dialect.isUniqueViolation(e)).as("a duplicate is recognised").isTrue();
        }

        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO " + dialect.qualify(table) + " ("
                    + dialect.quote("symb") + ", " + dialect.quote(IngestionId.COLUMN)
                    + ") VALUES (NULL, 'x:1')");
            org.assertj.core.api.Assertions.fail("expected a not-null violation");
        } catch (SQLException e) {
            assertThat(dialect.isUniqueViolation(e))
                    .as("a not-null violation shares the SQLSTATE and is not a duplicate").isFalse();
        }
    }

    @Test
    void aLargeReplayPagesAndReadsEverything() throws Exception {
        List<CdfBar> bars = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            bars.add(bar("1." + (i % 100), START + 100L + i));
        }
        load(sink("run-1", WriteMode.UPSERT_ON_NATURAL_KEY), bars);

        SqlSourceGateway<CdfBar> source = source(25);
        List<CdfBar> read = collect(source);
        assertThat(read).hasSize(250);
        assertThat(read).extracting(CdfBar::timestamp).isSorted();
        assertThat(source.pagesRead()).as("it really paged").isGreaterThan(1);
        assertThat(source.rowsFetched()).isEqualTo(source.rowsInWindow());
        assertThat(source.terminalFailure()).isEmpty();
    }

    // ------------------------------------------------------------------
    // Delivery semantics (section 7) against a real server
    // ------------------------------------------------------------------

    @Test
    void aCommitWhoseOutcomeIsUnknownAndDidNotLandIsResolvedByInserting() throws Exception {
        // The case section 7 exists for, on the engine it was written for. Only the commit
        // acknowledgement is simulated - and it has to be, because "the server committed and the
        // answer was lost" is by definition a client-side observation. Everything else is real: the
        // inserts, the ingestion-id primary key, and the lookup that resolves the outcome all run
        // against MySQL.
        List<CdfBar> bars = bars(5, START + 100);
        SqlSinkGateway sink = sinkOn(failingAtCommit(false), "run-unknown",
                WriteMode.UPSERT_ON_NATURAL_KEY);
        load(sink, bars);

        DeliveryAccounting.Counts c = sink.counts();
        assertThat(c.observed()).isEqualTo(5);
        assertThat(c.unknown()).as("the retry established what happened; nothing may be left unknown")
                .isZero();
        assertThat(c.committed()).isEqualTo(5);
        assertThat(c.isFinal()).isTrue();
        // Resolution inserted them, so MySQL really does hold exactly five.
        assertThat(rowCount()).as("committed must mean the rows are in the table").isEqualTo(5);
    }

    @Test
    void aCommitThatLandedButWasNotAcknowledgedDoesNotDuplicate() throws Exception {
        // The other half, and the one a naive retry gets wrong: the rows ARE there, so re-inserting
        // them must not double them. The proxy commits for real and then throws, so MySQL has genuinely
        // committed before the client is told anything.
        List<CdfBar> bars = bars(5, START + 100);
        SqlSinkGateway sink = sinkOn(failingAtCommit(true), "run-landed",
                WriteMode.UPSERT_ON_NATURAL_KEY);
        load(sink, bars);

        DeliveryAccounting.Counts c = sink.counts();
        assertThat(c.unknown()).isZero();
        assertThat(c.committed()).isEqualTo(5);
        assertThat(rowCount()).as("five rows, not ten - the ingestion id identified them")
                .isEqualTo(5);
    }

    @Test
    void anUnresolvableOutcomeStaysUnknownAndFailsAnEssentialRun() throws Exception {
        // Commit fails and the resolution cannot reach the database either. Neither "written" nor
        // "lost" is honest, so it stays unknown - and because the sink is essential, that fails.
        List<CdfBar> bars = bars(5, START + 100);
        SqlSinkGateway sink = sinkOn(unusableAfterFirstCommit(), "run-unresolvable",
                WriteMode.UPSERT_ON_NATURAL_KEY);

        Thread writer = new Thread(sink, "sink-writer");
        writer.start();
        for (CdfBar b : bars) sink.onEvent(TOPIC, b);
        sink.requestStop();
        writer.join(180_000);

        DeliveryAccounting.Counts c = sink.counts();
        assertThat(c.unknown()).as("it genuinely could not be established").isEqualTo(5);
        assertThat(c.committed()).isZero();
        assertThat(c.isFinal()).isTrue();
        assertThat(sink.terminalFailure()).as("an essential sink must report this").isPresent();
        assertThat(sink.terminalFailure().get().fatal()).isTrue();
    }

    @Test
    void recoveryDoesNotRestoreAValueThatWasSuperseded() throws Exception {
        // An upsert OVERWRITES the ingestion id of the row it replaces. So when two events in one
        // batch share a natural key, only the later one's id survives - and resolving by looking ids
        // up finds the earlier one "absent", re-applies it, and restores data the run had already
        // superseded, while reporting both as committed and no failure. Reproduced exactly that way
        // before the fix.
        BigDecimal first  = new BigDecimal("1.000000000000");
        BigDecimal second = new BigDecimal("2.000000000000");
        long when = START + 100;
        List<CdfBar> sameKey = List.of(barWith(first, when), barWith(second, when));

        SqlSinkGateway sink = sinkOn(failingAtCommit(true), "run-superseded",
                WriteMode.UPSERT_ON_NATURAL_KEY);
        load(sink, sameKey);

        assertThat(sink.counts().committed()).isEqualTo(2);
        assertThat(sink.counts().unknown()).isZero();
        assertThat(sink.counts().isFinal()).isTrue();
        assertThat(rowCount()).as("one natural key, one row").isEqualTo(1);

        // The surviving row must hold the LAST event's data, not the one it superseded.
        try (Connection c = ds.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT " + dialect.quote("op") + ", "
                     + dialect.quote(IngestionId.COLUMN) + " FROM " + dialect.qualify(table))) {
            rs.next();
            assertThat(rs.getBigDecimal(1))
                    .as("recovery must not reinstate the superseded value").isEqualByComparingTo(second);
            assertThat(IngestionId.parse(rs.getString(2)).sequence())
                    .as("the later event owns the row").isEqualTo(1L);
        }
    }

    // ------------------------------------------------------------------
    // The replay snapshot (section 3) - a claim we make about InnoDB
    // ------------------------------------------------------------------

    @Test
    void theSnapshotExcludesRowsCommittedDuringTheReplay() throws Exception {
        // supportsReplaySnapshot() returns true for MySQL. Until now the only test asserted that the
        // dialect returns what we hard-coded; this asserts InnoDB actually behaves that way.
        //
        // The inserted row is deliberately placed BELOW the upper bound and ABOVE the read cursor, so
        // the bound of section 3 would not exclude it. Only the snapshot can. If REPEATABLE READ were
        // not giving a consistent read for the whole replay, this row would appear on a later page.
        List<CdfBar> bars = bars(200, START + 1_000);
        load(sinkOn(ds, "run-seed", WriteMode.UPSERT_ON_NATURAL_KEY), bars);

        long intruderTime = START + 1_000 + 150;          // inside the window, below max(timestamp)
        SqlSourceGateway<CdfBar> source = source(10);
        assertThat(source.usedSnapshot() || true).isTrue();   // established once the run starts

        List<CdfBar> read = Collections.synchronizedList(new ArrayList<>());
        java.util.concurrent.atomic.AtomicBoolean inserted =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        AbstractGateway collector = new AbstractGateway("collector", START, END) {
            @Override public void run() { }
            @Override public <Q extends Datum> void onEvent(Topic<Q> t, Q p) {
                read.add((CdfBar) p);
                // On the first event, another connection commits a row into the window.
                if (inserted.compareAndSet(false, true)) {
                    try {
                        insertDirectly("INTRUDER", intruderTime, "run-intruder:0");
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
            @Override public <Q extends Datum> void publish(Topic<Q> t, Q p) { }
        };
        source.registerSubscriber(collector, TOPIC, List.of("CL", "INTRUDER"));
        source.run();

        assertThat(source.usedSnapshot()).as("MySQL is characterised for replay snapshots").isTrue();
        assertThat(source.terminalFailure()).isEmpty();
        assertThat(read).extracting(CdfBar::symb)
                .as("a row committed mid-replay must not appear, even below the bound")
                .doesNotContain("INTRUDER");
        assertThat(read).hasSize(200);
        assertThat(source.rowsFetched()).isEqualTo(source.rowsInWindow());
        // ...and it really is in the table, so the test proved exclusion rather than a failed insert.
        assertThat(rowCount()).isEqualTo(201);
    }

    // ------------------------------------------------------------------
    // Transport
    // ------------------------------------------------------------------

    @Test
    void theConnectionIsEncryptedWhenTheUrlAsksForIt() throws Exception {
        // CI used to connect with sslMode=DISABLED while the real deployment uses REQUIRED, so the TLS
        // path was never exercised. This asserts the session is genuinely encrypted whenever the URL
        // asks for it, rather than trusting the parameter was honoured.
        String url = property("pulse.it.mysql.url", "PULSE_IT_MYSQL_URL", "");
        boolean wantsTls = url.toLowerCase(java.util.Locale.ROOT).contains("sslmode=required")
                || url.toLowerCase(java.util.Locale.ROOT).contains("sslmode=verify");

        try (Connection c = ds.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SHOW STATUS LIKE 'Ssl_cipher'")) {
            rs.next();
            String cipher = rs.getString(2);
            if (wantsTls) {
                assertThat(cipher).as("the URL asked for TLS, so the session must be encrypted")
                        .isNotBlank();
            } else {
                // Not a failure: a local container may legitimately run without TLS. Reported, so the
                // gap is visible rather than silent.
                System.out.println("NOTE: connection is not encrypted (Ssl_cipher='"
                        + cipher + "'); the URL did not request TLS");
            }
        }
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static CdfBar bar(String price, long atMillis) {
        BigDecimal p = new BigDecimal(price);
        return new CdfBar("CL", atMillis, p, p, p, p, p, p,
                Instant.ofEpochMilli(atMillis), 42L, LocalDate.of(2026, 1, 2), null, null, null);
    }

    /** A bar with a chosen price at a chosen instant - same natural key for a fixed instant. */
    private static CdfBar barWith(BigDecimal price, long atMillis) {
        return new CdfBar("CL", atMillis, price, price, price, price, price, price,
                Instant.ofEpochMilli(atMillis), 42L, LocalDate.of(2026, 1, 2), null, null, null);
    }

    /** Several bars at consecutive event times. */
    private static List<CdfBar> bars(int n, long firstMillis) {
        List<CdfBar> bars = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            bars.add(bar("1." + (i % 97), firstMillis + i));
        }
        return bars;
    }

    /** Insert a row with a chosen key, time and ingestion id, on its own connection. */
    private void insertDirectly(String symb, long atMillis, String ingestionId) throws SQLException {
        String sql = "INSERT INTO " + dialect.qualify(table) + " ("
                + dialect.quote("symb") + ", " + dialect.quote("timestamp") + ", "
                + dialect.quote("op") + ", " + dialect.quote("hi") + ", " + dialect.quote("lo") + ", "
                + dialect.quote("cl") + ", " + dialect.quote("vlm") + ", "
                + dialect.quote("datetime") + ", " + dialect.quote("date") + ", "
                + dialect.quote(IngestionId.COLUMN) + ") VALUES (?, ?, 1, 1, 1, 1, 1,"
                + " '2026-01-02 00:00:00.000', '2026-01-02', ?)";
        try (Connection c = ds.getConnection();
             java.sql.PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, symb);
            ps.setLong(2, atMillis);
            ps.setString(3, ingestionId);
            ps.executeUpdate();
        }
    }

    /**
     * A DataSource over the real server whose first commit throws.
     *
     * @param commitForReal commit before throwing, so MySQL has genuinely committed and only the
     *                      acknowledgement is lost - which is precisely the case section 7 exists for
     */
    private DataSource failingAtCommit(boolean commitForReal) {
        java.util.concurrent.atomic.AtomicBoolean first =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        return proxied(c -> (p, m, a) -> {
            if ("commit".equals(m.getName()) && first.compareAndSet(false, true)) {
                if (commitForReal) c.commit();
                throw new SQLException("connection lost at commit", "08006");
            }
            return invokeOn(c, m, a);
        });
    }

    /**
     * The writer's connection works and inserts for real, but its commit throws; every connection
     * opened afterwards is unusable.
     *
     * <p>Numbered per connection rather than by a flag: the writer's own connection must be fully
     * functional, or the rows never reach MySQL and the batch is recorded as demonstrably
     * not-committed instead of unknown - which is a different case entirely.
     */
    private DataSource unusableAfterFirstCommit() {
        java.util.concurrent.atomic.AtomicInteger connections = new java.util.concurrent.atomic.AtomicInteger();
        return proxiedNumbered((c, n) -> (p, m, a) -> {
            String name = m.getName();
            if (n == 1 && "commit".equals(name)) {
                throw new SQLException("connection lost at commit", "08006");
            }
            if (n > 1 && ("prepareStatement".equals(name) || "createStatement".equals(name))) {
                // The resolution's fresh connection cannot be used, so the outcome is unknowable.
                throw new SQLException("no usable connection", "08006");
            }
            return invokeOn(c, m, a);
        }, connections);
    }

    /** As {@link #proxied}, but the handler is told which connection this is (1-based). */
    private DataSource proxiedNumbered(
            java.util.function.BiFunction<Connection, Integer, InvocationHandler> handler,
            java.util.concurrent.atomic.AtomicInteger counter) {
        DataSource real = ds;
        return (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    if (!"getConnection".equals(method.getName())) {
                        return invokeOn(real, method, args);
                    }
                    Connection c = real.getConnection();
                    int n = counter.incrementAndGet();
                    return Proxy.newProxyInstance(getClass().getClassLoader(),
                            new Class<?>[] {Connection.class}, handler.apply(c, n));
                });
    }

    /** Wrap each real connection in a handler built by {@code handler}. */
    private DataSource proxied(java.util.function.Function<Connection, InvocationHandler> handler) {
        DataSource real = ds;
        return (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    if (!"getConnection".equals(method.getName())) {
                        return invokeOn(real, method, args);
                    }
                    Connection c = real.getConnection();
                    return Proxy.newProxyInstance(getClass().getClassLoader(),
                            new Class<?>[] {Connection.class}, handler.apply(c));
                });
    }

    private static Object invokeOn(Object target, Method m, Object[] a) throws Throwable {
        try {
            return m.invoke(target, a);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private SqlSinkGateway sinkOn(DataSource on, String runId, WriteMode mode) {
        SqlSinkGateway sink = new SqlSinkGateway("sink", on, binding, dialect, runId,
                SinkFailurePolicy.ESSENTIAL, START, END, 10_000, 100, 50);
        sink.setWriteMode(mode);
        sink.setDrainTimeoutMillis(120_000);
        return sink;
    }

    private SqlSinkGateway sink(String runId, WriteMode mode) {
        SqlSinkGateway sink = new SqlSinkGateway("sink", ds, binding, dialect, runId,
                SinkFailurePolicy.ESSENTIAL, START, END, 10_000, 100, 50);
        sink.setWriteMode(mode);
        sink.setDrainTimeoutMillis(120_000);
        return sink;
    }

    private void load(SqlSinkGateway sink, List<CdfBar> bars) throws Exception {
        Thread writer = new Thread(sink, "sink-writer");
        writer.start();
        for (CdfBar b : bars) {
            sink.onEvent(TOPIC, b);
        }
        sink.requestStop();
        writer.join(180_000);
        assertThat(sink.isTerminated()).isTrue();
        assertThat(sink.terminalFailure()).isEmpty();
    }

    private SqlSourceGateway<CdfBar> source(int pageSize) {
        return new SqlSourceGateway<>("source", ds, binding, dialect, TOPIC, List.of(),
                START, END, pageSize);
    }

    private List<CdfBar> replay(int pageSize) {
        return collect(source(pageSize));
    }

    private List<CdfBar> collect(SqlSourceGateway<CdfBar> source) {
        List<CdfBar> read = Collections.synchronizedList(new ArrayList<>());
        AbstractGateway collector = new AbstractGateway("collector", START, END) {
            @Override public void run() { }
            @Override public <Q extends Datum> void onEvent(Topic<Q> t, Q p) { read.add((CdfBar) p); }
            @Override public <Q extends Datum> void publish(Topic<Q> t, Q p) { }
        };
        source.registerSubscriber(collector, TOPIC, List.of("CL"));
        source.run();
        return read;
    }

    private long rowCount() throws SQLException {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + dialect.qualify(table))) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String required(String property, String env) {
        String v = property(property, env, null);
        if (v == null || v.isBlank()) {
            throw new AssertionError(
                    "this test requires a real MySQL server: set -D" + property + " or $" + env
                    + ". It is excluded from the default build; CI runs it with a service container.");
        }
        return v;
    }

    private static String property(String property, String env, String fallback) {
        String v = System.getProperty(property);
        if (v == null || v.isBlank()) v = System.getenv(env);
        return v == null || v.isBlank() ? fallback : v;
    }

    private static String redact(String url) {
        return url.replaceAll("(?i)(password|user)=[^&;]*", "$1=***");
    }

    private static DataSource dataSource(String url, String user, String password) {
        return new DataSource() {
            @Override public Connection getConnection() throws SQLException {
                return DriverManager.getConnection(url, user, password);
            }
            @Override public Connection getConnection(String u, String p) throws SQLException {
                return DriverManager.getConnection(url, u, p);
            }
            @Override public PrintWriter getLogWriter()                 { return null; }
            @Override public void setLogWriter(PrintWriter out)         { }
            @Override public void setLoginTimeout(int seconds)          { }
            @Override public int getLoginTimeout()                      { return 0; }
            @Override public java.util.logging.Logger getParentLogger() { return null; }
            @Override public <T> T unwrap(Class<T> i) throws SQLException {
                throw new java.sql.SQLFeatureNotSupportedException();
            }
            @Override public boolean isWrapperFor(Class<?> i)           { return false; }
        };
    }
}
