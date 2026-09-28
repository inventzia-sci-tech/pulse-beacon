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
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
            markFailed("sink writer failed: " + t);
        } finally {
            stopping = true;
            closeQuietly(connection);
            connection = null;
            terminated = true;
        }
    }

    private void drain() throws InterruptedException {
        List<Pending> batch = new ArrayList<>(batchSize);
        while (true) {
            Pending p = queue.poll(lingerMillis, TimeUnit.MILLISECONDS);
            if (p != null) {
                batch.add(p);
                if (batch.size() >= batchSize) {
                    writeBatch(batch);
                    batch.clear();
                }
            } else {
                // Idle: commit what is waiting rather than holding it until the batch fills. A quiet
                // stream should not sit uncommitted for an unbounded time.
                if (!batch.isEmpty()) {
                    writeBatch(batch);
                    batch.clear();
                }
                if (stopping && queue.isEmpty()) {
                    return;
                }
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

        try (PreparedStatement ps = c.prepareStatement(dialect.insertStatement(binding))) {
            for (Pending p : batch) {
                try {
                    rowBinder.bind(ps, p.payload(), p.ingestionId());
                    ps.addBatch();
                    insertable.add(p);
                } catch (DatumRowBinder.ValueOutOfRangeException e) {
                    // One unrepresentable value must not cost the whole batch.
                    accounting.serializationError(1);
                    log.severe(name() + ": " + p.ingestionId() + " rejected: " + e.getMessage());
                }
            }
            if (insertable.isEmpty()) {
                return;
            }
            ps.executeBatch();
        } catch (SQLException e) {
            // Before the commit, so it can be undone and the outcome is knowable.
            rollbackQuietly(c);
            accounting.record(insertable.size(), DeliveryOutcome.NOT_COMMITTED);
            onPersistenceFailure("insert failed, " + insertable.size() + " rows not committed", e);
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
            resolveByRetry(insertable);
        }
    }

    /**
     * Retry an unknown batch to find out what actually happened.
     *
     * <p>The retry carries the same ingestion ids, so it either inserts — the earlier attempt had not
     * committed — or violates the unique constraint, which <em>proves</em> it had. Either way the
     * outcome becomes a fact. If the retry itself cannot reach the database, the batch stays
     * {@code unknown}, which is the honest answer.
     */
    private void resolveByRetry(List<Pending> batch) {
        if (!dialect.distinguishesUniqueViolation()) {
            return;    // cannot tell a duplicate from any other error here; unknown stays unknown
        }
        // A FRESH connection, and this is not a detail. Retrying on the connection whose commit failed
        // would insert against that same still-open transaction, and the unique violation it raised
        // would be against the retry's OWN pending rows - proving nothing, while looking exactly like
        // proof that the earlier attempt committed. That reports rows as committed when the table is
        // empty, which is the precise lie this whole design exists to prevent.
        try (Connection fresh = dataSource.getConnection()) {
            fresh.setAutoCommit(false);
            try (PreparedStatement ps = fresh.prepareStatement(dialect.insertStatement(binding))) {
                for (Pending p : batch) {
                    rowBinder.bind(ps, p.payload(), p.ingestionId());
                    ps.addBatch();
                }
                ps.executeBatch();
                fresh.commit();
                accounting.resolveUnknown(batch.size(), DeliveryOutcome.COMMITTED);
                log.info(name() + ": retry inserted " + batch.size()
                         + " rows; the earlier attempt had not committed");
            } catch (SQLException e) {
                rollbackQuietly(fresh);
                if (dialect.isUniqueViolation(e)) {
                    accounting.resolveUnknown(batch.size(), DeliveryOutcome.COMMITTED);
                    log.info(name() + ": retry hit the ingestion-id constraint, which proves the"
                             + " earlier attempt committed " + batch.size() + " rows");
                } else {
                    log.severe(name() + ": retry could not establish the outcome of " + batch.size()
                               + " rows; they remain unknown: " + e);
                }
            }
        } catch (SQLException e) {
            log.severe(name() + ": retry could not reach the database; " + batch.size()
                       + " rows remain unknown: " + e);
        } catch (RuntimeException e) {
            log.severe(name() + ": retry failed; " + batch.size() + " rows remain unknown: " + e);
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
            markFailed(what + ": " + cause);
        }
    }

    private void markFailed(String detail) {
        failed = true;
        failureDetail = detail;
        log.severe(name() + ": " + detail);
    }

    // ------------------------------------------------------------------
    // Dispatch thread
    // ------------------------------------------------------------------

    @Override
    public <P extends Datum> void onEvent(Topic<P> topic, P payload) {
        accounting.observe(1);
        if (stopping || failed) {
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
}
