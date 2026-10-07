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
    // Fixtures
    // ------------------------------------------------------------------

    private static CdfBar bar(String price, long atMillis) {
        BigDecimal p = new BigDecimal(price);
        return new CdfBar("CL", atMillis, p, p, p, p, p, p,
                Instant.ofEpochMilli(atMillis), 42L, LocalDate.of(2026, 1, 2), null, null, null);
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
