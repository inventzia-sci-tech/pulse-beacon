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

import com.inventzia.pulse.beacon.core.Topic;
import com.inventzia.pulse.data.schemas.platform.HeartBeat;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The sink end to end: generated DDL, real inserts, and the three outcomes told apart.
 *
 * <p>The outcome tests are the point. Two of the three are easy to reach by breaking something; the
 * third — a commit that lands and whose acknowledgement is lost — is the one that cannot be observed
 * from inside the client, so it is simulated exactly: the proxy commits for real and <em>then</em>
 * throws.
 */
class SqlSinkGatewayTest {

    private String       dbName;
    private DataSource   ds;
    private SqlDialect   dialect;
    private SqlTableBinding binding;

    private static final QualifiedTableName BEATS = QualifiedTableName.of("heartbeats");
    private static final String RUN_ID = "2026-09-28T12-00-00Z-test";

    @BeforeEach
    void setUp() throws Exception {
        dbName  = "sink_" + UUID.randomUUID().toString().replace("-", "");
        ds      = h2();
        dialect = new H2Dialect();
        binding = SqlTableBinding.of(BEATS, StorageMapping.of(HeartBeat.class));
        provision(ds);
    }

    @AfterEach
    void tearDown() throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP ALL OBJECTS");
        }
    }

    private DataSource h2() {
        JdbcDataSource d = new JdbcDataSource();
        d.setURL("jdbc:h2:mem:" + dbName + ";DB_CLOSE_DELAY=-1");
        d.setUser("sa");
        return d;
    }

    /** The full provisioning act: register, create from generated DDL, mark ready. */
    private void provision(DataSource on) throws SQLException {
        TableRegistry registry = new TableRegistry(on);
        registry.ensureRegistryTable();
        registry.beginProvisioning(binding, "test");
        try (Connection c = on.getConnection(); Statement st = c.createStatement()) {
            st.execute(dialect.createTableStatement(binding));
        }
        registry.markReady(BEATS);
    }

    private SqlSinkGateway sink(DataSource on, SinkFailurePolicy policy) {
        return new SqlSinkGateway("sink", on, binding, dialect, RUN_ID, policy, 0, Long.MAX_VALUE,
                1000, 10, 50);
    }

    /** Run the sink over the given beats and wait for the writer to finish. */
    private SqlSinkGateway runSink(DataSource on, SinkFailurePolicy policy, int beats)
            throws Exception {
        SqlSinkGateway sink = sink(on, policy);
        Thread writer = new Thread(sink, "sink-writer");
        writer.start();
        Topic<HeartBeat> topic = new Topic<>("beats", HeartBeat.class);
        for (int i = 0; i < beats; i++) {
            sink.onEvent(topic, new HeartBeat("beat-" + i, 1_000L + i));
        }
        sink.requestStop();
        writer.join(15_000);
        assertThat(sink.isTerminated()).as("writer thread finished").isTrue();
        return sink;
    }

    private long rowCount() throws SQLException {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + dialect.qualify(BEATS))) {
            rs.next();
            return rs.getLong(1);
        }
    }

    // ------------------------------------------------------------------
    // The happy path, end to end
    // ------------------------------------------------------------------

    @Test
    void generatedDdlProducesATableTheSinkCanWriteAndReadBack() throws Exception {
        SqlSinkGateway sink = runSink(ds, SinkFailurePolicy.OBSERVATIONAL, 25);

        assertThat(rowCount()).isEqualTo(25);
        DeliveryAccounting.Counts c = sink.counts();
        assertThat(c.observed()).isEqualTo(25);
        assertThat(c.committed()).isEqualTo(25);
        assertThat(c.unknown()).isZero();
        assertThat(c.notCommitted()).isZero();
        assertThat(c.balances()).isTrue();
        assertThat(sink.hasFailed()).isFalse();
    }

    @Test
    void everyRowCarriesItsDeterministicIngestionId() throws Exception {
        runSink(ds, SinkFailurePolicy.OBSERVATIONAL, 5);

        try (Connection c = ds.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT " + dialect.quote(IngestionId.COLUMN) + " FROM " + dialect.qualify(BEATS)
                     + " ORDER BY " + dialect.quote("beatTime"))) {
            int i = 0;
            while (rs.next()) {
                IngestionId id = IngestionId.parse(rs.getString(1));
                assertThat(id.runId()).isEqualTo(RUN_ID);
                assertThat(id.sequence()).isEqualTo(i++);
            }
            assertThat(i).isEqualTo(5);
        }
    }

    @Test
    void theDerivedTimestampMatchesTheEpochMillisItIsGeneratedFrom() throws Exception {
        // It is generated, not written, so the two cannot disagree - that is the whole argument for it.
        runSink(ds, SinkFailurePolicy.OBSERVATIONAL, 3);

        try (Connection c = ds.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT " + dialect.quote("beatTime") + ", "
                     + dialect.quote(dialect.derivedTimestampColumn())
                     + " FROM " + dialect.qualify(BEATS))) {
            int seen = 0;
            while (rs.next()) {
                long millis = rs.getLong(1);
                java.sql.Timestamp derived = rs.getTimestamp(2,
                        java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")));
                assertThat(derived.getTime()).isEqualTo(millis);
                seen++;
            }
            assertThat(seen).isEqualTo(3);
        }
    }

    @Test
    void theSinkRefusesToPublish() {
        // Sink-only by construction, not by a flag that makes half the API a runtime error.
        assertThatThrownBy(() -> sink(ds, SinkFailurePolicy.OBSERVATIONAL)
                .publish(new Topic<>("beats", HeartBeat.class), new HeartBeat("k", 1)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ------------------------------------------------------------------
    // The three outcomes
    // ------------------------------------------------------------------

    @Test
    void aFailedInsertIsDemonstrablyNotCommitted() throws Exception {
        // Fails before the commit, so it can be rolled back and the outcome is knowable. It must not
        // be recorded as unknown - that would waste the one category reserved for genuine ignorance.
        DataSource faulty = failing(FailAt.EXECUTE, false);
        SqlSinkGateway sink = runSink(faulty, SinkFailurePolicy.OBSERVATIONAL, 10);

        DeliveryAccounting.Counts c = sink.counts();
        assertThat(c.notCommitted()).isPositive();
        assertThat(c.unknown()).as("a rollback-able failure is not unknown").isZero();
        assertThat(c.committed()).isZero();
        assertThat(rowCount()).isZero();
        assertThat(c.balances()).isTrue();
    }

    @Test
    void aLostAcknowledgementOnACommitThatLandedResolvesToCommitted() throws Exception {
        // The case the whole three-outcome design exists for. The proxy commits for real and then
        // throws, exactly as a connection lost at commit would. The retry hits the ingestion-id
        // constraint, which proves the rows are there.
        DataSource faulty = failing(FailAt.COMMIT, true);
        SqlSinkGateway sink = runSink(faulty, SinkFailurePolicy.OBSERVATIONAL, 10);

        DeliveryAccounting.Counts c = sink.counts();
        assertThat(rowCount()).as("the rows really did land").isEqualTo(10);
        assertThat(c.unknown()).as("the retry resolved it; it must not be left unknown").isZero();
        assertThat(c.committed()).isEqualTo(10);
        assertThat(c.balances()).isTrue();
    }

    @Test
    void aCommitThatDidNotLandIsResolvedByTheRetryInserting() throws Exception {
        // The other half: the commit threw and the rows were NOT there. The retry inserts them, and
        // the outcome is equally a fact - resolved the opposite way from the test above.
        DataSource faulty = failing(FailAt.COMMIT, false);
        SqlSinkGateway sink = runSink(faulty, SinkFailurePolicy.OBSERVATIONAL, 10);

        DeliveryAccounting.Counts c = sink.counts();
        assertThat(c.unknown()).isZero();
        assertThat(c.committed()).isEqualTo(10);
        // The regression guard. Retrying on the failed connection would violate the unique constraint
        // against the retry's own uncommitted rows and report all ten committed with the table empty.
        assertThat(rowCount()).as("committed must mean the rows are actually there").isEqualTo(10);
    }

    @Test
    void anUnresolvableCommitStaysUnknownRatherThanGuessing() throws Exception {
        // Commit throws and the retry cannot reach the database either. Neither neighbour is honest,
        // so the batch stays unknown and the manifest will say so.
        DataSource faulty = failing(FailAt.COMMIT_AND_RETRY, false);
        SqlSinkGateway sink = runSink(faulty, SinkFailurePolicy.OBSERVATIONAL, 10);

        DeliveryAccounting.Counts c = sink.counts();
        assertThat(c.unknown()).as("it genuinely could not be established").isEqualTo(10);
        assertThat(c.committed()).isZero();
        assertThat(c.notCommitted()).isZero();
        assertThat(c.balances()).isTrue();
    }

    // ------------------------------------------------------------------
    // Failure policy
    // ------------------------------------------------------------------

    @Test
    void anObservationalSinkDoesNotFailTheRun() throws Exception {
        SqlSinkGateway sink = runSink(failing(FailAt.EXECUTE, false),
                SinkFailurePolicy.OBSERVATIONAL, 10);
        assertThat(sink.hasFailed()).as("losing a recording beats killing a live run").isFalse();
        assertThat(sink.counts().hasFailures()).as("but it is still recorded, loudly").isTrue();
    }

    @Test
    void anEssentialSinkFailsTheRun() throws Exception {
        SqlSinkGateway sink = runSink(failing(FailAt.EXECUTE, false),
                SinkFailurePolicy.ESSENTIAL, 10);
        assertThat(sink.hasFailed()).isTrue();
        assertThat(sink.failureDetail()).isNotBlank();
    }

    // ------------------------------------------------------------------
    // Startup validation
    // ------------------------------------------------------------------

    @Test
    void validateRefusesATableEveryInsertWouldFailOn() throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("ALTER TABLE " + dialect.qualify(BEATS)
                    + " ADD COLUMN ingested_by VARCHAR(64) NOT NULL DEFAULT 'x'");
            st.execute("ALTER TABLE " + dialect.qualify(BEATS)
                    + " ALTER COLUMN ingested_by DROP DEFAULT");
        }
        assertThatThrownBy(() -> sink(ds, SinkFailurePolicy.OBSERVATIONAL).validate())
                .isInstanceOf(BindingMismatchException.class)
                .hasMessageContaining("every insert would fail");
    }

    @Test
    void validateRefusesAnUnregisteredTable() throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("DELETE FROM " + TableRegistry.REGISTRY_TABLE);
        }
        assertThatThrownBy(() -> sink(ds, SinkFailurePolicy.OBSERVATIONAL).validate())
                .isInstanceOf(BindingMismatchException.class);
    }

    @Test
    void validateAcceptsAProperlyProvisionedTable() throws Exception {
        sink(ds, SinkFailurePolicy.OBSERVATIONAL).validate();      // must not throw
    }

    // ------------------------------------------------------------------
    // A fault-injecting DataSource
    // ------------------------------------------------------------------

    private enum FailAt { EXECUTE, COMMIT, COMMIT_AND_RETRY }

    /**
     * A DataSource whose connections fail at a chosen point.
     *
     * @param commitForReal when failing at commit, commit first and then throw — which is precisely
     *                      a connection lost after the database committed
     */
    private DataSource failing(FailAt where, boolean commitForReal) {
        DataSource real = h2();
        AtomicBoolean firstCommitDone = new AtomicBoolean(false);
        return (DataSource) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    if (!"getConnection".equals(method.getName())) {
                        return method.invoke(real, args);
                    }
                    Connection c = real.getConnection();
                    return Proxy.newProxyInstance(getClass().getClassLoader(),
                            new Class<?>[] {Connection.class},
                            new ConnectionFault(c, where, commitForReal, firstCommitDone));
                });
    }

    private record ConnectionFault(Connection delegate, FailAt where, boolean commitForReal,
                                   AtomicBoolean firstCommitDone) implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if ("prepareStatement".equals(name) && where == FailAt.EXECUTE) {
                return failingStatement((java.sql.PreparedStatement) invoke(method, args));
            }
            if ("commit".equals(name)) {
                boolean first = firstCommitDone.compareAndSet(false, true);
                boolean shouldFail = first || where == FailAt.COMMIT_AND_RETRY;
                if (where != FailAt.EXECUTE && shouldFail) {
                    if (commitForReal) delegate.commit();     // it landed; the answer was lost
                    throw new SQLException("connection lost at commit", "08006");
                }
            }
            return invoke(method, args);
        }

        private Object invoke(Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(delegate, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }
        }

        /** A statement whose executeBatch always fails, before any commit. */
        private static java.sql.PreparedStatement failingStatement(java.sql.PreparedStatement real) {
            return (java.sql.PreparedStatement) Proxy.newProxyInstance(
                    ConnectionFault.class.getClassLoader(),
                    new Class<?>[] {java.sql.PreparedStatement.class},
                    (p, m, a) -> {
                        if (m.getName().startsWith("executeBatch")) {
                            throw new SQLException("insert failed", "23000");
                        }
                        try {
                            return m.invoke(real, a);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }
    }
}
