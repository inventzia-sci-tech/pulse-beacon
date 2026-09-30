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

import com.inventzia.pulse.beacon.core.GatewayStatus;
import com.inventzia.pulse.beacon.core.MultiClientEngine;
import com.inventzia.pulse.beacon.core.RunListener;
import com.inventzia.pulse.beacon.core.RunOutcome;
import com.inventzia.pulse.beacon.core.examples.RunUtils;
import com.inventzia.pulse.beacon.core.gateway.file.JsonlReaderGateway;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
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

    private SqlTableBinding binding() { return binding; }

    /**
     * The first commit throws without the rows landing, after running {@code justBeforeThrowing} —
     * which is how a state that only appears between the failed commit and the resolution is set up.
     */
    private DataSource failingAtCommitAfter(Runnable justBeforeThrowing) {
        DataSource real = h2();
        java.util.concurrent.atomic.AtomicBoolean first =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        return (DataSource) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    if (!"getConnection".equals(method.getName())) {
                        return method.invoke(real, args);
                    }
                    Connection c = real.getConnection();
                    return Proxy.newProxyInstance(getClass().getClassLoader(),
                            new Class<?>[] {Connection.class},
                            (p2, m2, a2) -> {
                                if ("commit".equals(m2.getName()) && first.compareAndSet(false, true)) {
                                    justBeforeThrowing.run();
                                    throw new SQLException("connection lost at commit", "08006");
                                }
                                return invokeOn(c, m2, a2);
                            });
                });
    }

    private void executeOnDb(String sql) {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * The first connection's commit throws with the rows never landing; the resolution connection's
     * insert then fails with the given SQLSTATE - an integrity error that is not a duplicate.
     */
    private DataSource failingWithResolutionError(String sqlState) {
        DataSource real = h2();
        java.util.concurrent.atomic.AtomicInteger connections =
                new java.util.concurrent.atomic.AtomicInteger();
        return (DataSource) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    if (!"getConnection".equals(method.getName())) {
                        return method.invoke(real, args);
                    }
                    Connection c = real.getConnection();
                    boolean isResolution = connections.getAndIncrement() > 0;
                    return Proxy.newProxyInstance(getClass().getClassLoader(),
                            new Class<?>[] {Connection.class},
                            (p2, m2, a2) -> {
                                if (!isResolution && "commit".equals(m2.getName())) {
                                    throw new SQLException("connection lost at commit", "08006");
                                }
                                if (isResolution && "prepareStatement".equals(m2.getName())) {
                                    java.sql.PreparedStatement realPs =
                                            (java.sql.PreparedStatement) invokeOn(c, m2, a2);
                                    return Proxy.newProxyInstance(getClass().getClassLoader(),
                                            new Class<?>[] {java.sql.PreparedStatement.class},
                                            (p3, m3, a3) -> {
                                                if (m3.getName().startsWith("executeBatch")) {
                                                    throw new SQLException("not null", sqlState);
                                                }
                                                return invokeOn(realPs, m3, a3);
                                            });
                                }
                                return invokeOn(c, m2, a2);
                            });
                });
    }

    private static Object invokeOn(Object target, Method m, Object[] a) throws Throwable {
        try {
            return m.invoke(target, a);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
        }
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
    // Resolution must establish facts, not read them off an error code
    // ------------------------------------------------------------------

    @Test
    void anIntegrityErrorThatIsNotADuplicateDoesNotEstablishDelivery() throws Exception {
        // The bug: SQLSTATE class 23 is "integrity constraint violation" generally - 23502 is NOT NULL,
        // 23503 foreign key, 23513 a check. Treating the class as "duplicate" turns an insert that
        // failed for an unrelated reason into proof that an earlier attempt committed, and books the
        // whole batch as delivered while the table holds none of it.
        DataSource faulty = failingWithResolutionError("23502");
        SqlSinkGateway sink = runSink(faulty, SinkFailurePolicy.OBSERVATIONAL, 10);

        DeliveryAccounting.Counts c = sink.counts();
        assertThat(rowCount()).as("nothing landed").isZero();
        assertThat(c.committed()).as("a not-null violation is not proof of delivery").isZero();
        assertThat(c.unknown()).as("it genuinely could not be established").isEqualTo(10);
        assertThat(c.balances()).isTrue();
    }

    @Test
    void aUniqueViolationOnADifferentConstraintDoesNotEstablishDelivery() throws Exception {
        // Even a genuine 23505 proves nothing by itself: it may be raised by any unique constraint on
        // the table - a natural-key index, say - and says nothing about whether THESE ingestion ids
        // were stored. The old code read any class-23 error as "the earlier attempt committed".
        DataSource faulty = failingWithResolutionError("23505");
        SqlSinkGateway sink = runSink(faulty, SinkFailurePolicy.OBSERVATIONAL, 10);

        DeliveryAccounting.Counts c = sink.counts();
        assertThat(rowCount()).as("nothing landed").isZero();
        assertThat(c.committed())
                .as("another constraint's violation says nothing about our ingestion ids").isZero();
        assertThat(c.unknown()).isEqualTo(10);
        assertThat(c.balances()).isTrue();
    }

    @Test
    void aPartiallyCommittedBatchIsCompletedAndReported() throws Exception {
        // A batch whose transaction was not atomic. The lookup finds some ids present, and only the
        // genuinely missing rows are inserted - no duplicates, nothing left behind.
        // Simulate a transaction that landed only part of its batch: commit for real, then remove all
        // but the first four rows, then report failure. Four are stored, six are not.
        DataSource faulty = failingAtCommitPartially(4);
        SqlSinkGateway run = runSink(faulty, SinkFailurePolicy.OBSERVATIONAL, 10);

        assertThat(run.counts().committed()).isEqualTo(10);
        assertThat(run.counts().unknown()).isZero();
        assertThat(rowCount()).as("the four already there plus the six missing, and no duplicates")
                .isEqualTo(10);
    }

    /** Commits for real, keeps only the first {@code keep} rows, then throws at commit. */
    private DataSource failingAtCommitPartially(int keep) {
        DataSource real = h2();
        java.util.concurrent.atomic.AtomicBoolean first =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        return (DataSource) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    if (!"getConnection".equals(method.getName())) {
                        return method.invoke(real, args);
                    }
                    Connection c = real.getConnection();
                    return Proxy.newProxyInstance(getClass().getClassLoader(),
                            new Class<?>[] {Connection.class},
                            (p2, m2, a2) -> {
                                if ("commit".equals(m2.getName()) && first.compareAndSet(false, true)) {
                                    invokeOn(c, m2, a2);          // it really committed...
                                    executeOnDb("DELETE FROM " + dialect.qualify(BEATS) + " WHERE "
                                            + dialect.quote("beatTime") + " >= " + (1_000L + keep));
                                    throw new SQLException("connection lost at commit", "08006");
                                }
                                return invokeOn(c, m2, a2);
                            });
                });
    }

    @Test
    void onlyARealDuplicateCountsAsOne() {
        // Unit-level guard on the classification itself.
        SqlDialect d = new H2Dialect();
        assertThat(d.isUniqueViolation(new SQLException("dup", "23505"))).isTrue();
        assertThat(d.isUniqueViolation(new SQLException("not null", "23502"))).isFalse();
        assertThat(d.isUniqueViolation(new SQLException("fk", "23503"))).isFalse();
        assertThat(d.isUniqueViolation(new SQLException("check", "23513"))).isFalse();
    }

    // ------------------------------------------------------------------
    // Batch age, not idle time
    // ------------------------------------------------------------------

    @Test
    void aSteadyStreamCommitsOnBatchAgeRatherThanWaitingForTheBatchToFill() throws Exception {
        // An idle timeout is reset by every arrival, so a stream arriving just faster than the linger
        // never times out: nothing reaches the database until the batch is full. With the real
        // defaults that is ~100 seconds for 500 rows. The wait must be bounded by the age of the
        // batch instead, measured from the event that opened it.
        long linger = 200;
        SqlSinkGateway sink = new SqlSinkGateway("sink", ds, binding, dialect, RUN_ID,
                SinkFailurePolicy.OBSERVATIONAL, 0, Long.MAX_VALUE,
                1000, 500, linger);          // a batch the stream will never fill
        Thread writer = new Thread(sink, "sink-writer");
        writer.start();

        Topic<HeartBeat> topic = new Topic<>("beats", HeartBeat.class);
        long start = System.currentTimeMillis();
        long deadline = start + 8_000;
        int sent = 0;
        long firstRowSeenAt = -1;

        // Feed steadily, faster than the linger, and watch for the first committed row.
        while (System.currentTimeMillis() < deadline && firstRowSeenAt < 0 && sent < 400) {
            sink.onEvent(topic, new HeartBeat("beat-" + sent, 1_000L + sent));
            sent++;
            Thread.sleep(linger / 4);        // well inside the linger: an idle timeout never fires
            if (rowCount() > 0) {
                firstRowSeenAt = System.currentTimeMillis();
            }
        }
        sink.requestStop();
        writer.join(10_000);

        assertThat(firstRowSeenAt).as("rows must reach the database without the batch filling")
                .isGreaterThan(0);
        assertThat(sent).as("the batch of 500 was never filled, so only age can have flushed it")
                .isLessThan(500);
        long waited = firstRowSeenAt - start;
        assertThat(waited).as("bounded by the batch age, not by the batch size")
                .isLessThan(linger * 10);
    }

    // ------------------------------------------------------------------
    // Nothing may be left "in flight" once the writer has exited
    // ------------------------------------------------------------------

    @Test
    void aStatementPreparationFailureAccountsForTheWholeBatch() throws Exception {
        // The trap: the handler recorded insertable.size(), which is still zero when prepareStatement
        // itself is what failed. The batch then had no outcome at all, and the books balanced only
        // because the missing events were never named.
        SqlSinkGateway sink = runSink(failingToPrepare(), SinkFailurePolicy.OBSERVATIONAL, 4);

        DeliveryAccounting.Counts c = sink.counts();
        assertThat(c.observed()).isEqualTo(4);
        assertThat(c.notCommitted()).as("every accepted event must have an outcome").isEqualTo(4);
        assertThat(c.inFlight()).isZero();
        assertThat(c.isFinal()).isTrue();
        assertThat(c.hasFailures()).isTrue();
    }

    @Test
    void eventsArrivingAfterTheSinkStoppedAreCountedAsAbandoned() throws Exception {
        SqlSinkGateway sink = sink(ds, SinkFailurePolicy.OBSERVATIONAL);
        Thread writer = new Thread(sink, "sink-writer");
        writer.start();
        sink.requestStop();
        writer.join(10_000);
        assertThat(sink.isTerminated()).isTrue();

        // The engine can still dispatch here: the sink is a subscriber, not the run's owner.
        sink.onEvent(new Topic<>("beats", HeartBeat.class), new HeartBeat("late", 1));

        DeliveryAccounting.Counts c = sink.counts();
        assertThat(c.observed()).isEqualTo(1);
        assertThat(c.abandoned()).as("accepted and never attempted is its own outcome").isEqualTo(1);
        assertThat(c.inFlight()).isZero();
        assertThat(c.hasFailures()).as("a terminated sink holding an unwritten event is not healthy")
                .isTrue();
    }

    @Test
    void eventsStillQueuedWhenTheWriterExitsAreCountedAsAbandoned() throws Exception {
        // The writer dies with work outstanding. Those events were accepted and never written, and
        // must be named rather than left to look like nothing happened.
        SqlSinkGateway sink = new SqlSinkGateway("sink", ds, binding, dialect, RUN_ID,
                SinkFailurePolicy.OBSERVATIONAL, 0, Long.MAX_VALUE, 1000, 10_000, 50);
        Topic<HeartBeat> topic = new Topic<>("beats", HeartBeat.class);
        for (int i = 0; i < 6; i++) {
            sink.onEvent(topic, new HeartBeat("beat-" + i, 1_000L + i));
        }
        // Never started a writer thread; run() then drains and finalizes immediately.
        sink.requestStop();
        sink.run();

        DeliveryAccounting.Counts c = sink.counts();
        assertThat(c.observed()).isEqualTo(6);
        assertThat(c.inFlight()).as("the books must close").isZero();
        assertThat(c.isFinal()).isTrue();
        assertThat(c.committed() + c.abandoned()).isEqualTo(6);
    }

    @Test
    void anInterruptedWriterStillAccountsForWhatItWasHolding() throws Exception {
        // The writer exits with work outstanding. Those events were accepted and never written; if
        // finalization does not drain the queue they stay in flight forever, and a terminated sink
        // reports no failures while events are simply missing.
        SqlSinkGateway sink = new SqlSinkGateway("sink", ds, binding, dialect, RUN_ID,
                SinkFailurePolicy.OBSERVATIONAL, 0, Long.MAX_VALUE,
                1000, 10_000, 60_000);        // huge batch, long linger: it parks holding everything
        Thread writer = new Thread(sink, "sink-writer");
        writer.start();
        Topic<HeartBeat> topic = new Topic<>("beats", HeartBeat.class);
        for (int i = 0; i < 5; i++) {
            sink.onEvent(topic, new HeartBeat("beat-" + i, 1_000L + i));
        }
        // Give the writer a moment to park in poll() with the queue non-empty, then kill it.
        long deadline = System.currentTimeMillis() + 5_000;
        while (sink.counts().observed() < 5 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        writer.interrupt();
        writer.join(10_000);

        assertThat(sink.isTerminated()).isTrue();
        DeliveryAccounting.Counts c = sink.counts();
        assertThat(c.observed()).isEqualTo(5);
        assertThat(c.inFlight()).as("the books must close even when the writer was killed").isZero();
        assertThat(c.isFinal()).isTrue();
        assertThat(c.abandoned()).isEqualTo(5);
        assertThat(c.hasFailures()).isTrue();
        assertThat(rowCount()).isZero();
    }

    @Test
    void eventsLeftInTheQueueWhenTheWriterNeverConsumesThemAreAbandoned() throws Exception {
        // The other half of finalization, and distinct from the test above: there the events were held
        // in the writer's current batch, here they never left the queue. Both have to be classified,
        // so both need their own case.
        SqlSinkGateway sink = new SqlSinkGateway("sink", ds, binding, dialect, RUN_ID,
                SinkFailurePolicy.OBSERVATIONAL, 0, Long.MAX_VALUE, 1000, 10_000, 60_000);
        Topic<HeartBeat> topic = new Topic<>("beats", HeartBeat.class);
        for (int i = 0; i < 5; i++) {
            sink.onEvent(topic, new HeartBeat("beat-" + i, 1_000L + i));
        }

        // Run the writer on this thread with the interrupt flag already set: the very first poll
        // throws, so nothing is ever consumed and all five are still queued at finalization.
        Thread.currentThread().interrupt();
        sink.run();
        assertThat(Thread.interrupted()).as("clear the flag so it cannot leak into other tests")
                .isTrue();

        DeliveryAccounting.Counts c = sink.counts();
        assertThat(c.observed()).isEqualTo(5);
        assertThat(c.abandoned()).as("never consumed, so never written").isEqualTo(5);
        assertThat(c.inFlight()).isZero();
        assertThat(c.isFinal()).isTrue();
        assertThat(rowCount()).isZero();
    }

    @Test
    void aCleanRunClosesItsBooksWithNoFailures() throws Exception {
        // The control. Without it, all of the above would pass just as well if every event were
        // classified as a failure.
        SqlSinkGateway sink = runSink(ds, SinkFailurePolicy.OBSERVATIONAL, 8);

        DeliveryAccounting.Counts c = sink.counts();
        assertThat(c.committed()).isEqualTo(8);
        assertThat(c.abandoned()).isZero();
        assertThat(c.inFlight()).isZero();
        assertThat(c.isFinal()).isTrue();
        assertThat(c.hasFailures()).isFalse();
    }

    /** A DataSource whose connections cannot prepare a statement at all. */
    private DataSource failingToPrepare() {
        DataSource real = h2();
        return (DataSource) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    if (!"getConnection".equals(method.getName())) {
                        return method.invoke(real, args);
                    }
                    Connection c = real.getConnection();
                    return Proxy.newProxyInstance(getClass().getClassLoader(),
                            new Class<?>[] {Connection.class},
                            (p2, m2, a2) -> {
                                if ("prepareStatement".equals(m2.getName())) {
                                    throw new SQLException("cannot prepare", "42000");
                                }
                                return invokeOn(c, m2, a2);
                            });
                });
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
    void anEssentialSinkReportsThroughTheGatewayFailureChannel() throws Exception {
        // A local flag is read by nobody outside this class. The engine learns through
        // failTerminally, so that is what has to be populated.
        SqlSinkGateway sink = runSink(failing(FailAt.EXECUTE, false),
                SinkFailurePolicy.ESSENTIAL, 10);

        assertThat(sink.hasFailed()).isTrue();
        assertThat(sink.terminalFailure()).as("the engine can only see what is reported here")
                .isPresent();
        assertThat(sink.terminalFailure().get().fatal()).isTrue();
        assertThat(sink.terminalFailure().get().gatewayName()).isEqualTo("sink");
    }

    @Test
    void anUnresolvedCommitFailsAnEssentialRun() throws Exception {
        // "We cannot tell whether your data was written" is no better than "it was not" for an
        // application whose output IS the database. An unresolvable outcome is a persistence failure
        // like any other, and the declared policy has to apply to it.
        SqlSinkGateway sink = runSink(failing(FailAt.COMMIT_AND_RETRY, false),
                SinkFailurePolicy.ESSENTIAL, 10);

        assertThat(sink.counts().unknown()).as("it genuinely could not be established").isEqualTo(10);
        assertThat(sink.terminalFailure()).as("and that must reach the engine").isPresent();
        assertThat(sink.terminalFailure().get().fatal()).isTrue();
        assertThat(sink.terminalFailure().get().detail()).contains("remain unknown");
    }

    @Test
    void anUnresolvedCommitDoesNotFailAnObservationalRun() throws Exception {
        // The control: the policy still decides, so this must not become fatal for everyone.
        SqlSinkGateway sink = runSink(failing(FailAt.COMMIT_AND_RETRY, false),
                SinkFailurePolicy.OBSERVATIONAL, 10);

        assertThat(sink.counts().unknown()).isEqualTo(10);
        assertThat(sink.hasFailed()).isFalse();
        assertThat(sink.counts().hasFailures()).as("still recorded, just not fatal").isTrue();
    }

    @Test
    void anUnresolvedCommitFailsTheRunUnderARunningEngine() throws Exception {
        RunOutcome outcome = runEngineWithSink(failing(FailAt.COMMIT_AND_RETRY, false),
                SinkFailurePolicy.ESSENTIAL);

        assertThat(outcome).isNotNull();
        assertThat(outcome.runStatus()).isEqualTo("failed");
        assertThat(outcome.failedGateways()).contains("sink");
    }

    @Test
    void anObservationalSinkDoesNotDeclareItselfFatal() {
        // The other side of the boundary: the policy must not quietly make every sink able to kill a
        // run, or the default would be the dangerous one.
        assertThat(sink(ds, SinkFailurePolicy.OBSERVATIONAL).failureIsFatal()).isFalse();
        assertThat(sink(ds, SinkFailurePolicy.ESSENTIAL).failureIsFatal()).isTrue();
    }

    @Test
    void anEssentialSinkFailsTheRunUnderARunningEngine() throws Exception {
        // The claim in the name, actually tested: a real engine run, and its recorded outcome.
        RunOutcome outcome = runEngineWithSink(failing(FailAt.EXECUTE, false),
                SinkFailurePolicy.ESSENTIAL);

        assertThat(outcome).isNotNull();
        assertThat(outcome.runStatus())
                .as("an application whose output IS the database must not report success")
                .isEqualTo("failed");
        assertThat(outcome.failedGateways()).contains("sink");
        assertThat(outcome.describe()).contains("sink");
    }

    @Test
    void anObservationalSinkLetsTheRunCompleteUnderARunningEngine() throws Exception {
        // The control. Without it the test above would pass just as well if every sink failed the run.
        RunOutcome outcome = runEngineWithSink(failing(FailAt.EXECUTE, false),
                SinkFailurePolicy.OBSERVATIONAL);

        assertThat(outcome).isNotNull();
        assertThat(outcome.runStatus()).isEqualTo("completed");
        assertThat(outcome.fatalFailures()).isEmpty();
    }

    @Test
    void aFailureDuringTheFinalDrainStillFailsTheRun() throws Exception {
        // The easiest failure to miss. With fewer events than the batch size nothing is written until
        // the sink is asked to stop, so the only commit happens while the run is already shutting down
        // - after the dispatch loop has ended, which is exactly when a late failure could be dropped.
        SqlSinkGateway sink = new SqlSinkGateway("sink", failing(FailAt.EXECUTE, false), binding,
                dialect, RUN_ID, SinkFailurePolicy.ESSENTIAL, 0, Long.MAX_VALUE,
                1000, 10_000, 50);          // batch far larger than the event count
        Thread writer = new Thread(sink, "sink-writer");
        writer.start();
        Topic<HeartBeat> topic = new Topic<>("beats", HeartBeat.class);
        for (int i = 0; i < 3; i++) {
            sink.onEvent(topic, new HeartBeat("beat-" + i, 1_000L + i));
        }
        assertThat(rowCount()).as("nothing written yet; the batch is not full").isZero();

        sink.requestStop();                 // the only write happens here, in the drain
        writer.join(15_000);

        assertThat(sink.isTerminated()).isTrue();
        assertThat(sink.terminalFailure()).as("a failure at drain time must still be reported")
                .isPresent();
        assertThat(sink.terminalFailure().get().fatal()).isTrue();
        assertThat(sink.counts().notCommitted()).isEqualTo(3);
        assertThat(sink.counts().balances()).isTrue();
    }

    @Test
    void aDrainTimeFailureUnderARunningEngineStillFailsTheRun() throws Exception {
        // The case the shutdown barrier exists for. With a batch far larger than the event count,
        // nothing is written while the engine dispatches - the only commit happens during shutdown.
        // Without the sink acking the engine's shutdown barrier, its writer thread outlives the
        // decision it should inform: the run completes, the outcome is published, and only then does
        // the closing write fail. A whole run's tail can be lost that way.
        RunOutcome outcome = runEngineWithSink(failing(FailAt.EXECUTE, false),
                SinkFailurePolicy.ESSENTIAL, 10_000);

        assertThat(outcome).isNotNull();
        assertThat(outcome.runStatus()).as("the closing writes never landed").isEqualTo("failed");
        assertThat(outcome.failedGateways()).contains("sink");
    }

    private RunOutcome runEngineWithSink(DataSource on, SinkFailurePolicy policy) throws Exception {
        return runEngineWithSink(on, policy, 5);
    }

    /**
     * Run a real engine: a JSONL source publishes heartbeats, the sink subscribes, and the engine's
     * own {@link RunOutcome} is what gets asserted.
     *
     * @param batchSize larger than the event count to force all writing into the final drain
     */
    private RunOutcome runEngineWithSink(DataSource on, SinkFailurePolicy policy, int batchSize)
            throws Exception {
        long start = 1_000L, end = 9_000L;
        Path file = Files.createTempFile("sink-engine", ".jsonl");
        file.toFile().deleteOnExit();
        List<String> lines = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            lines.add("{\"beatKey\":\"BEAT\",\"beatTime\":" + (start + i * 100) + "}");
        }
        Files.write(file, lines);

        Topic<HeartBeat> topic = new Topic<>("heartbeat", HeartBeat.class);
        MultiClientEngine engine = new MultiClientEngine("engine", start, end);
        JsonlReaderGateway<HeartBeat> reader =
                new JsonlReaderGateway<>("Reader", topic, List.of("BEAT"), file, start, end);
        SqlSinkGateway sink = new SqlSinkGateway("sink", on, binding, dialect, RUN_ID, policy,
                start, end, 1000, batchSize, 50);

        AtomicReference<RunOutcome> seen = new AtomicReference<>();
        engine.addRunListener(new RunListener() {
            @Override public void onRunTerminated(RunOutcome outcome) { seen.set(outcome); }
        });
        engine.registerPublisher(reader, topic, List.of("BEAT"));
        engine.registerSubscriber(sink, topic, List.of("BEAT"));

        Thread engineThread = new Thread(engine, "engine");
        Thread writerThread = new Thread(sink, "sink-writer");
        engineThread.start();
        RunUtils.awaitStatus(engine, GatewayStatus.STARTED, 3_000);
        writerThread.start();
        new Thread(reader, "reader").start();

        engineThread.join(15_000);
        sink.requestStop();
        writerThread.join(10_000);
        assertThat(engineThread.isAlive()).as("the run terminated").isFalse();
        return seen.get();
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
