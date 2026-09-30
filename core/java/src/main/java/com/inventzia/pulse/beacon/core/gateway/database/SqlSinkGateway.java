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
import com.inventzia.pulse.beacon.core.AbstractGateway;
import com.inventzia.pulse.data.datum.Datum;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Writes events into a SQL table.
 *
 * <p>A sink and only a sink: registered with {@code registerSubscriber}, {@code setDriveClock(false)},
 * and {@link #publish} throws. A combined source/sink class would make half its public API a runtime
 * error decided by a constructor flag — the old {@code CsvGateway}, with its nullable reader and writer
 * and its {@code if (reader != null)} run loop, is the counter-example (§1).
 *
 * <p><b>The outcome of a batch is one of three things, and the difference is where the failure lands.</b>
 * A failure in {@code executeBatch} can be rolled back, so it is demonstrably
 * {@link DeliveryOutcome#NOT_COMMITTED}. A failure <em>in {@code commit()}</em> is genuinely
 * {@link DeliveryOutcome#UNKNOWN}: the database may have committed and lost the acknowledgement. Calling
 * that "lost" would be a lie, and retrying blindly could duplicate. So the batch is retried carrying the
 * same {@link IngestionId}s, and a unique violation on them <em>proves</em> the earlier attempt
 * committed (§7).
 *
 * <p><b>Failures do not kill the run by default.</b> {@link SinkFailurePolicy#OBSERVATIONAL} matches
 * {@code RunRecording}: losing a recording is better than killing a live run. An application whose
 * essential output <em>is</em> the database declares {@link SinkFailurePolicy#ESSENTIAL} and gets the
 * opposite.
 *
 * <p>See {@code docs/pulse-sql-gateway.md} §1, §7.
 */
public class SqlSinkGateway extends AbstractGateway {

    /** Events buffered before the writer thread must catch up. */
    public static final int DEFAULT_QUEUE_CAPACITY = 10_000;

    /** Rows per transaction. */
    public static final int DEFAULT_BATCH_SIZE = 500;

    /** How long the writer waits for a full batch before committing what it has. */
    public static final long DEFAULT_LINGER_MILLIS = 200;

    /** How long the engine's shutdown barrier waits for the final drain. */
    public static final long DEFAULT_DRAIN_TIMEOUT_MILLIS = 30_000;

    private final DataSource         dataSource;
    private final SqlTableBinding    binding;
    private final SqlDialect         dialect;
    private final String             runId;
    private final SinkFailurePolicy  failurePolicy;
    private final DatumRowBinder     rowBinder;
    private final DeliveryAccounting accounting = new DeliveryAccounting();

    private final BlockingQueue<Pending> queue;
    private final int  batchSize;
    private final long lingerMillis;

    /** How long the engine's shutdown barrier waits for the writer to drain. */
    private volatile long drainTimeoutMillis = DEFAULT_DRAIN_TIMEOUT_MILLIS;

    private final AtomicLong sequence = new AtomicLong();

    /** Writer-thread only. Replaced whenever its state can no longer be reasoned about. */
    private Connection connection;

    private volatile boolean stopping   = false;
    private volatile boolean failed     = false;
    private volatile boolean terminated = false;
    private volatile String  failureDetail;

    /** One event captured on the dispatch thread, written on the writer thread. */
    private record Pending(IngestionId ingestionId, Datum payload) {}

    public SqlSinkGateway(String name, DataSource dataSource, SqlTableBinding binding,
                          SqlDialect dialect, String runId, SinkFailurePolicy failurePolicy,
                          long startTime, long endTime) {
        this(name, dataSource, binding, dialect, runId, failurePolicy, startTime, endTime,
             DEFAULT_QUEUE_CAPACITY, DEFAULT_BATCH_SIZE, DEFAULT_LINGER_MILLIS);
    }

    public SqlSinkGateway(String name, DataSource dataSource, SqlTableBinding binding,
                          SqlDialect dialect, String runId, SinkFailurePolicy failurePolicy,
                          long startTime, long endTime,
                          int queueCapacity, int batchSize, long lingerMillis) {
        super(name, startTime, endTime);
        this.dataSource    = Objects.requireNonNull(dataSource, "dataSource");
        this.binding       = Objects.requireNonNull(binding, "binding");
        this.dialect       = Objects.requireNonNull(dialect, "dialect");
        this.runId         = Objects.requireNonNull(runId, "runId");
        this.failurePolicy = Objects.requireNonNull(failurePolicy, "failurePolicy");
        this.rowBinder     = new DatumRowBinder(binding.mapping());
        this.queue         = new ArrayBlockingQueue<>(Math.max(1, queueCapacity));
        this.batchSize     = Math.max(1, batchSize);
        this.lingerMillis  = Math.max(1, lingerMillis);
        setDriveClock(false);      // a sink never drives the clock
        // The declared policy IS the gateway's fatality: an application whose essential output is the
        // database wants a persistence failure to fail the run, and the engine is what can do that.
        // Without this the policy would only ever set a local flag nobody outside the sink reads.
        setFailureIsFatal(failurePolicy.isFatal());
    }

    // ------------------------------------------------------------------
    // Startup validation
    // ------------------------------------------------------------------

    /**
     * Check the table before the run starts: registered, ready, and writable.
     *
     * <p>At startup rather than on the first write, because an extra {@code NOT NULL} column with no
     * default reads perfectly and makes every insert fail — and discovering that mid-run makes a
     * configuration problem look like a data problem.
     *
     * @throws BindingMismatchException if the registration or the table disagrees with this binding
     */
    public void validate() throws SQLException {
        new TableRegistry(dataSource).requireReady(binding);
        try (Connection c = dataSource.getConnection()) {
            TableValidator.validateForSink(c, binding).orThrow();
        }
    }

    // ------------------------------------------------------------------
    // Writer thread
    // ------------------------------------------------------------------

    @Override
    public void run() {
        try {
            drain();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            // The writer thread is gone, so the sink is terminal whatever the policy says. The policy
            // still decides whether that ends the run.
            markFailed("sink writer failed", t);
        } finally {
            stopping = true;
            finalizeAccounting();
            closeQuietly(connection);
            connection = null;
            terminated = true;
        }
    }

    /**
     * Classify everything still outstanding, so no event is left "in flight" after the writer exits.
     *
     * <p>In-flight is a meaningful state only while the sink is running. Once it has stopped, an event
     * with no outcome is simply missing — and the books would balance, and {@code hasFailures()} would
     * say no, purely because nobody counted it.
     */
    private void finalizeAccounting() {
        List<Pending> stranded = new ArrayList<>();
        queue.drainTo(stranded);
        if (!stranded.isEmpty()) {
            accounting.abandoned(stranded.size());
            log.severe(name() + ": " + stranded.size() + " events were still queued when the writer"
                       + " stopped; they were never written");
        }
        DeliveryAccounting.Counts c = accounting.counts();
        if (!c.isFinal()) {
            // A bug in this class rather than in the database, and exactly the kind that would
            // otherwise surface as a manifest quietly understating what was lost.
            log.severe(name() + ": accounting did not close - " + c.inFlight()
                       + " events have no outcome: " + c);
        }
    }

    private void drain() throws InterruptedException {
        List<Pending> batch = new ArrayList<>(batchSize);
        try {
            drainLoop(batch);
        } finally {
            // Whatever is still in hand when the loop ends abnormally was accepted and never written.
            if (!batch.isEmpty()) {
                accounting.abandoned(batch.size());
                log.severe(name() + ": " + batch.size() + " events were held but never written");
            }
        }
    }

    private void drainLoop(List<Pending> batch) throws InterruptedException {
        // The age of the batch, measured from the event that opened it - NOT the time since the last
        // arrival. An idle timeout is reset by every event, so a stream arriving just faster than the
        // linger never times out and the batch is only committed once it is full: at 199ms spacing and
        // the default batch of 500, that is a hundred seconds before anything reaches the database.
        // A maximum age bounds the wait regardless of arrival rate.
        long batchDeadline = 0;

        while (true) {
            long waitMillis = batch.isEmpty()
                    ? lingerMillis
                    : Math.max(0L, (batchDeadline - System.nanoTime()) / 1_000_000L);

            Pending p = queue.poll(waitMillis, TimeUnit.MILLISECONDS);
            if (p != null) {
                if (batch.isEmpty()) {
                    batchDeadline = System.nanoTime() + lingerMillis * 1_000_000L;
                }
                batch.add(p);
            }

            boolean full = batch.size() >= batchSize;
            boolean aged = !batch.isEmpty() && System.nanoTime() >= batchDeadline;
            if (full || aged) {
                writeBatch(batch);
                batch.clear();
            }
            if (p == null && stopping && queue.isEmpty()) {
                if (!batch.isEmpty()) {          // commit the tail before leaving
                    writeBatch(batch);
                    batch.clear();
                }
                return;
            }
        }
    }

    /**
     * The writer's connection, opened on demand.
     *
     * <p>Replaceable rather than held for the whole run: a connection whose commit outcome is unknown
     * is itself in an unknown state, and continuing to use it would carry that ambiguity into every
     * later batch.
     */
    private Connection connection() throws SQLException {
        if (connection == null) {
            connection = dataSource.getConnection();
            connection.setAutoCommit(false);   // batches are transactions; that is what accounts for them
        }
        return connection;
    }

    /** Abandon a connection we can no longer reason about. The next batch opens a fresh one. */
    private void discardConnection() {
        closeQuietly(connection);
        connection = null;
    }

    private void closeQuietly(Connection c) {
        if (c == null) return;
        try {
            c.close();
        } catch (SQLException e) {
            log.warn(name() + ": closing connection failed: " + e);
        }
    }

    /**
     * Insert and commit one batch, classifying the outcome into exactly one of the three.
     *
     * <p>The ordering matters: everything that can be rolled back is attempted before the commit, so
     * that {@code unknown} is reserved for the one case that genuinely is.
     */
    private void writeBatch(List<Pending> batch) {
        int n = batch.size();
        List<Pending> insertable = new ArrayList<>(n);
        Connection c;
        try {
            c = connection();
        } catch (SQLException e) {
            accounting.record(n, DeliveryOutcome.NOT_COMMITTED);
            onPersistenceFailure("no connection, " + n + " rows not committed", e);
            return;
        }

        int rejected = 0;
        try (PreparedStatement ps = c.prepareStatement(dialect.insertStatement(binding))) {
            for (Pending p : batch) {
                try {
                    rowBinder.bind(ps, p.payload(), p.ingestionId());
                    ps.addBatch();
                    insertable.add(p);
                } catch (DatumRowBinder.ValueOutOfRangeException e) {
                    // One unrepresentable value must not cost the whole batch.
                    accounting.serializationError(1);
                    rejected++;
                    log.severe(name() + ": " + p.ingestionId() + " rejected: " + e.getMessage());
                }
            }
            if (insertable.isEmpty()) {
                return;                       // every row was rejected, and each is already counted
            }
            ps.executeBatch();
        } catch (SQLException e) {
            // Before the commit, so it can be undone and the outcome is knowable.
            rollbackQuietly(c);
            // Everything not already counted as a serialization error - NOT insertable.size(), which
            // is still zero when prepareStatement itself was what failed. Using it there would leave
            // the whole batch unclassified and looking like nothing had gone wrong.
            int unaccounted = n - rejected;
            accounting.record(unaccounted, DeliveryOutcome.NOT_COMMITTED);
            onPersistenceFailure("insert failed, " + unaccounted + " rows not committed", e);
            return;
        }

        try {
            c.commit();
            accounting.record(insertable.size(), DeliveryOutcome.COMMITTED);
        } catch (SQLException e) {
            // The one genuinely unknown case: the database may have committed and the acknowledgement
            // may have been lost. Neither neighbour is honest here.
            accounting.record(insertable.size(), DeliveryOutcome.UNKNOWN);
            log.severe(name() + ": commit outcome unknown for " + insertable.size() + " rows: " + e);
            // The connection is now unreasonable-about: its transaction may or may not have committed.
            discardConnection();
            resolveByVerification(insertable);
        }
    }

    /**
     * Establish what actually happened to an unknown batch, by looking it up.
     *
     * <p><b>Facts, not inferences.</b> The tempting version of this asks "did the retry raise a unique
     * violation?" and treats yes as proof the earlier attempt committed. It is wrong twice over: SQLSTATE
     * class 23 covers not-null and foreign-key violations too, so an insert that failed for an unrelated
     * reason would read as proof; and even a genuine {@code 23505} may come from some other unique
     * constraint on the table and say nothing about these rows. Either way a whole batch would be booked
     * as committed while the table held none of it.
     *
     * <p>So the outcome is resolved by reading back which ingestion ids are stored, and inserting only
     * what is genuinely missing. The id is deterministic from {@code (runId, sequence)} and no other run
     * can produce it, so a stored id is necessarily this run's row — which is why the identity settles it
     * and the payload need not be compared.
     *
     * <p>A <b>partial</b> result is possible and is reported rather than smoothed over: it means the
     * batch's transaction was not atomic, which is worth an operator knowing. If the lookup itself cannot
     * be performed, the batch stays {@code unknown} — the honest answer.
     */
    private void resolveByVerification(List<Pending> batch) {
        // A FRESH connection, and not a detail. The connection whose commit failed may hold an open
        // transaction; querying or inserting on it would see that transaction's own pending rows and
        // prove nothing.
        try (Connection fresh = dataSource.getConnection()) {
            fresh.setAutoCommit(false);

            Set<String> stored = storedIngestionIds(fresh, batch);
            List<Pending> missing = batch.stream()
                    .filter(p -> !stored.contains(p.ingestionId().encoded()))
                    .toList();

            if (missing.isEmpty()) {
                accounting.resolveUnknown(batch.size(), DeliveryOutcome.COMMITTED);
                log.info(name() + ": all " + batch.size() + " rows are present, so the earlier attempt"
                         + " committed despite the lost acknowledgement");
                return;
            }
            if (missing.size() < batch.size()) {
                log.severe(name() + ": the batch committed only partially - " + stored.size() + " of "
                           + batch.size() + " rows are present; inserting the remainder");
            }
            insertAndCommit(fresh, missing);
            accounting.resolveUnknown(batch.size(), DeliveryOutcome.COMMITTED);
            log.info(name() + ": inserted the " + missing.size() + " rows that were missing; the batch"
                     + " is now committed in full");

        } catch (SQLException e) {
            unresolved(batch.size(), e);
        } catch (RuntimeException e) {
            unresolved(batch.size(), e);
        }
    }

    /**
     * The outcome of a batch could not be established, and stays {@link DeliveryOutcome#UNKNOWN}.
     *
     * <p>This is a persistence failure like any other, and the declared policy applies to it. For an
     * application whose essential output <em>is</em> the database, "we cannot tell whether your data
     * was written" is no better than "it was not" — so an essential sink must fail the run here too,
     * not merely log. Leaving it at a log entry is how a run finishes green with a hole in the data
     * that nobody can even size.
     */
    private void unresolved(int rows, Exception cause) {
        onPersistenceFailure("could not establish the outcome of " + rows
                             + " rows; they remain unknown", cause);
    }

    /** Which of the batch's ingestion ids are already in the table, asked in bounded chunks. */
    private Set<String> storedIngestionIds(Connection c, List<Pending> batch) throws SQLException {
        Set<String> stored = new HashSet<>();
        int chunk = Math.max(1, dialect.maxInListSize());
        for (int from = 0; from < batch.size(); from += chunk) {
            List<Pending> slice = batch.subList(from, Math.min(from + chunk, batch.size()));
            String sql = dialect.selectStoredIngestionIdsStatement(binding, slice.size());
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < slice.size(); i++) {
                    ps.setString(i + 1, slice.get(i).ingestionId().encoded());
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        stored.add(rs.getString(1));
                    }
                }
            }
        }
        return stored;
    }

    /** Insert exactly these rows and commit them. */
    private void insertAndCommit(Connection c, List<Pending> rows) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(dialect.insertStatement(binding))) {
            for (Pending p : rows) {
                rowBinder.bind(ps, p.payload(), p.ingestionId());
                ps.addBatch();
            }
            ps.executeBatch();
            c.commit();
        } catch (SQLException e) {
            rollbackQuietly(c);
            throw e;
        }
    }

    private void rollbackQuietly(Connection c) {
        try {
            c.rollback();
        } catch (SQLException e) {
            log.severe(name() + ": rollback failed: " + e);
        }
    }

    /**
     * Apply the declared failure policy.
     *
     * <p>Loud either way. The policy decides whether it is also fatal, never whether it is reported.
     */
    private void onPersistenceFailure(String what, Throwable cause) {
        log.severe(name() + ": " + what + ": " + cause);
        if (failurePolicy.isFatal()) {
            // Essential: the database IS this application's output, so there is nothing useful left to
            // do. Reported through the gateway failure channel, which is what reaches the engine and
            // ends the run - a local flag would only ever be read by this class.
            markFailed(what, cause);
        }
        // Observational: keep going. The failure is still counted, and the counts travel into the run
        // manifest (§7), so it is recorded rather than silent - it simply does not stop the run.
    }

    /**
     * The sink can do no more: stop accepting, and tell the engine.
     *
     * <p>{@link AbstractGateway#failTerminally} is what makes this visible outside the sink. Whether it
     * ends the run is the declared policy, applied through {@code setFailureIsFatal} at construction.
     */
    private void markFailed(String detail, Throwable cause) {
        failed = true;
        failureDetail = detail + (cause != null ? ": " + cause : "");
        failTerminally(detail, cause);
    }

    // ------------------------------------------------------------------
    // Dispatch thread
    // ------------------------------------------------------------------

    @Override
    public <P extends Datum> void onEvent(Topic<P> topic, P payload) {
        accounting.observe(1);
        if (stopping || failed) {
            // Accepted by the engine but never attempted. Counting it is what keeps the books from
            // balancing only because the missing events were never named.
            accounting.abandoned(1);
            return;
        }
        try {
            IngestionId id = new IngestionId(runId, sequence.getAndIncrement());
            if (!queue.offer(new Pending(id, payload))) {
                accounting.overflow(1);
            }
        } catch (RuntimeException e) {
            accounting.serializationError(1);
            log.severe(name() + ": rejected an event: " + e);
        }
    }

    /** A sink does not publish; see §1 on why this is a separate class rather than a mode. */
    @Override
    public <P extends Datum> void publish(Topic<P> topic, P payload) {
        throw new UnsupportedOperationException(name() + ": SqlSinkGateway does not publish");
    }

    // ------------------------------------------------------------------
    // Lifecycle and reporting
    // ------------------------------------------------------------------

    /** Ask the writer to finish: stop accepting, drain what is queued, commit, return. */
    public void requestStop() {
        stopping = true;
    }

    /**
     * The engine's shutdown barrier: drain and commit before the run is allowed to complete.
     *
     * <p>Without this the sink's writer thread outlives the decision it should inform. The engine
     * finishes dispatching, publishes its {@link com.inventzia.pulse.beacon.core.RunOutcome}, and only
     * afterwards does the last batch get written — so a failure in the <em>final drain</em>, which is
     * where a whole run's tail can be lost, arrives too late to fail anything. An essential sink would
     * report a completed run whose closing writes never landed.
     *
     * <p>Bounded: a sink that cannot finish must not hang the run forever. Exceeding the limit is
     * itself a terminal failure, since the queued events are unaccounted for.
     */
    @Override
    protected void onShutDown(long timeMillis) {
        requestStop();
        long deadline = System.nanoTime() + drainTimeoutMillis * 1_000_000L;
        while (!terminated && System.nanoTime() < deadline) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (!terminated) {
            markFailed("did not finish draining within " + drainTimeoutMillis + "ms; "
                       + queue.size() + " events are unaccounted for", null);
        }
    }

    /** The books. All three outcomes go into the run manifest; none is folded into another. */
    public DeliveryAccounting.Counts counts() {
        return accounting.counts();
    }

    /** Whether the sink failed under a policy that makes that fatal. */
    public boolean hasFailed() {
        return failed;
    }

    /** Why it failed, or {@code null}. */
    public String failureDetail() {
        return failureDetail;
    }

    /** Whether the writer thread has finished. */
    public boolean isTerminated() {
        return terminated;
    }

    /** The table this sink writes to. */
    public SqlTableBinding binding() {
        return binding;
    }

    /** How long the engine's shutdown barrier will wait for this sink to drain. */
    public void setDrainTimeoutMillis(long millis) {
        this.drainTimeoutMillis = Math.max(1, millis);
    }
}
