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
import com.inventzia.pulse.beacon.core.Gateway;
import com.inventzia.pulse.beacon.core.GatewayStatus;
import com.inventzia.pulse.beacon.core.Topic;
import com.inventzia.pulse.data.datum.Datum;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Streams events out of a SQL table as a historical source.
 *
 * <p>A source and only a source: registered with {@code registerPublisher}, it drives the clock and
 * takes part in the TimeMachine's permit protocol, and {@link #onEvent} throws. See §1 on why this is
 * a separate class from {@link SqlSinkGateway} rather than a mode on one.
 *
 * <p><b>Reproducibility rests on three things, none of which is optional.</b>
 * <ul>
 *   <li><b>A stable ordering tuple.</b> SQL provides no row order without {@code ORDER BY}, and
 *       ordering by the time column alone is not a total order — ties can come back differently
 *       between runs, engines or parallel plans. The TimeMachine cannot repair a non-deterministic
 *       source; it faithfully preserves whatever order it was given (§2).</li>
 *   <li><b>An upper bound, always.</b> Fixed at startup and carried on every page, so an appending
 *       feed cannot let later pages see rows earlier ones did not. Portable, no held transaction, no
 *       locks (§3).</li>
 *   <li><b>A snapshot, where the dialect says it is both available and acceptable.</b> An enhancement
 *       on top of the bound, never a replacement for it, and off by default for any engine nobody has
 *       characterised (§3).</li>
 * </ul>
 *
 * <p>Reads are paged by keyset, never {@code OFFSET}, so a large table replays in bounded memory.
 *
 * <p><b>Failure ends the run when the launcher says so.</b> A replay that stops halfway produces a
 * result derived from partial history, which is worse than no result — so a failing source both
 * disconnects (releasing the barrier) and reports the failure through
 * {@link AbstractGateway#failTerminally}. Whether that is fatal is declared with
 * {@code setFailureIsFatal} (§8).
 */
public class SqlSourceGateway<P extends Datum> extends AbstractGateway {

    /** Rows per page. Bounded memory matters more than a large table's page count. */
    public static final int DEFAULT_PAGE_SIZE = 1_000;

    private final DataSource         dataSource;
    private final SqlTableBinding    binding;
    private final SqlDialect         dialect;
    private final Topic<P>           topic;
    private final List<String>       keys;
    private final DatumRowReader<P>  rowReader;
    private final int                pageSize;

    private volatile long upperBound     = Long.MIN_VALUE;
    private volatile long rowsInWindow   = -1;
    private volatile long rowsRead       = 0;
    private volatile long rowsFetched    = 0;
    private volatile long pagesRead      = 0;
    private volatile boolean usedSnapshot = false;

    public SqlSourceGateway(String name, DataSource dataSource, SqlTableBinding binding,
                            SqlDialect dialect, Topic<P> topic, List<String> keys,
                            long startTime, long endTime) {
        this(name, dataSource, binding, dialect, topic, keys, startTime, endTime, DEFAULT_PAGE_SIZE);
    }

    public SqlSourceGateway(String name, DataSource dataSource, SqlTableBinding binding,
                            SqlDialect dialect, Topic<P> topic, List<String> keys,
                            long startTime, long endTime, int pageSize) {
        super(name, startTime, endTime);
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.binding    = Objects.requireNonNull(binding, "binding");
        this.dialect    = Objects.requireNonNull(dialect, "dialect");
        this.topic      = Objects.requireNonNull(topic, "topic");
        this.keys       = List.copyOf(Objects.requireNonNull(keys, "keys"));
        this.rowReader  = new DatumRowReader<>(binding.mapping());
        this.pageSize   = Math.max(1, pageSize);
        if (!binding.isReplayable()) {
            throw new IllegalArgumentException(name + ": " + binding.table().qualified()
                    + " has no ordering tuple, so it cannot be replayed reproducibly");
        }
        setDriveClock(true);
    }

    /**
     * Check the table before the run starts: registered, ready, and readable.
     *
     * <p>Read-only — a source runs against a pre-provisioned binding with read-only credentials, and
     * ordinary startup must not require DDL rights or registry writes (§1).
     */
    public void validate() throws SQLException {
        new TableRegistry(dataSource).requireReady(binding);
        try (Connection c = dataSource.getConnection()) {
            TableValidator.validateForSource(c, binding, dialect).orThrow();
        }
    }

    // ------------------------------------------------------------------
    // Runnable — the read loop
    // ------------------------------------------------------------------

    @Override
    public void run() {
        initialize();
        connect();
        setStatus(GatewayStatus.STARTED);

        try (Connection c = dataSource.getConnection()) {
            openSnapshotIfSupported(c);
            fixUpperBound(c);
            replay(c);
            verifyNothingWasSkipped();
            if (usedSnapshot) {
                c.commit();        // end the snapshot; nothing was written
            }
        } catch (Exception e) {
            // Disconnecting below releases the all-drivers barrier so the run terminates rather than
            // hanging - but on its own it is indistinguishable from reaching the end of the table.
            // Reporting the failure is what stops a half-finished replay counting as a completed run.
            log.severe("gateway '" + name() + "' failed reading " + binding.table().qualified()
                    + " after " + rowsRead + " rows; disconnecting so the run can terminate", e);
            failTerminally("failed reading " + binding.table().qualified()
                    + " after " + rowsRead + " of " + rowsInWindow + " rows", e);
        } finally {
            disconnect();
            setStatus(GatewayStatus.STOPPED);
        }
    }

    /**
     * Take a snapshot where the dialect says holding one for a replay is acceptable.
     *
     * <p>Two questions answered as one, deliberately: PostgreSQL <em>has</em> snapshot isolation, but a
     * long-running snapshot blocks vacuum and causes bloat, so "it exists" is not "hold it for twenty
     * minutes". The bound is what every dialect relies on; this is the enhancement.
     */
    private void openSnapshotIfSupported(Connection c) throws SQLException {
        if (!dialect.supportsReplaySnapshot()) {
            return;
        }
        c.setAutoCommit(false);
        c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        usedSnapshot = true;
        log.info(name() + ": replaying under a " + dialect.name() + " snapshot");
    }

    /**
     * Fix the upper bound and the expected row count once, before the first page.
     *
     * <p>Both go into the run's reporting: a dataset that changed underneath is then detectable after
     * the fact even where it could not be prevented.
     */
    private void fixUpperBound(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(dialect.selectUpperBoundStatement(binding))) {
            ps.setLong(1, startTime());
            ps.setLong(2, endTime());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long max = rs.getLong(1);
                    upperBound   = rs.wasNull() ? startTime() - 1 : max;   // empty window
                    rowsInWindow = rs.getLong(2);
                } else {
                    upperBound   = startTime() - 1;
                    rowsInWindow = 0;
                }
            }
        }
        log.info(name() + ": replaying " + rowsInWindow + " rows up to event time " + upperBound);
    }

    /** Page through the window in order, publishing each row. */
    private void replay(Connection c) throws SQLException {
        List<Object> cursor = null;
        while (connected()) {
            List<P> page = new ArrayList<>(pageSize);
            List<Object> lastKey = readPage(c, cursor, page);
            if (page.isEmpty()) {
                return;                      // the window is exhausted
            }
            // A cursor that does not advance means the next page would repeat this one forever. It
            // can only happen if the ordering tuple is not actually unique, or the comparison is
            // wrong - and the symptom is a replay that never ends and never says why, which is far
            // worse to diagnose than a failure. So it is checked rather than assumed.
            if (cursor != null && cursor.equals(lastKey)) {
                throw new NonAdvancingCursorException(name(), binding.orderingColumns(), lastKey);
            }
            pagesRead++;
            for (P payload : page) {
                if (!connected()) return;    // the engine is tearing down; stop promptly
                publishDownstream(payload);
            }
            cursor = lastKey;
        }
    }

    /** The paging cursor did not move, so reading on would repeat the same page forever. */
    public static class NonAdvancingCursorException extends RuntimeException {
        /** Serialised only if a caller chooses to; fixed so a future field cannot silently
         *  change the identity of an already-serialised instance. */
        private static final long serialVersionUID = 1L;

        public NonAdvancingCursorException(String gateway, List<String> ordering, List<Object> at) {
            super(gateway + ": the paging cursor did not advance past " + at + " on ordering tuple "
                  + ordering + "; the tuple is not unique, or the page comparison is wrong."
                  + " Refusing to read the same page forever.");
        }
    }

    /**
     * Read one page, collecting the rows and the ordering values to resume from.
     *
     * @return the last row's ordering tuple, or {@code null} if the page was empty
     */
    private List<Object> readPage(Connection c, List<Object> cursor, List<P> into)
            throws SQLException {
        String sql = dialect.selectPageStatement(binding, cursor != null);
        List<Object> lastKey = null;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int p = 1;
            ps.setLong(p++, startTime());
            ps.setLong(p++, Math.min(endTime(), upperBound));
            if (cursor != null) {
                for (Object value : cursor) {
                    ps.setObject(p++, value);
                }
            }
            ps.setInt(p, pageSize);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    into.add(rowReader.read(rs));
                    lastKey = currentOrderingValues(rs);
                    rowsFetched++;            // before the key filter, to compare with the row count
                }
            }
        }
        return lastKey;
    }

    /**
     * Check that the replay actually read the window it measured.
     *
     * <p>The count was taken at startup and the read is bounded by it, so the two must agree. They
     * disagree if the ordering tuple was not unique after all — keyset paging then skips every row
     * after the first at a duplicated value, silently, because the cursor still advances and no page
     * is re-read — or if rows were deleted mid-replay.
     *
     * <p>Cheap, and the last line of defence: a source can be run without calling {@link #validate()},
     * and this still catches it. Silently replaying a subset is the worst available outcome, because
     * every downstream result looks perfectly well-formed.
     */
    private void verifyNothingWasSkipped() {
        if (rowsInWindow >= 0 && rowsFetched != rowsInWindow) {
            throw new IncompleteReplayException(name(), binding.orderingColumns(),
                    rowsFetched, rowsInWindow);
        }
    }

    /** The replay did not read every row the window contained. */
    public static class IncompleteReplayException extends RuntimeException {
        /** Serialised only if a caller chooses to; fixed so a future field cannot silently
         *  change the identity of an already-serialised instance. */
        private static final long serialVersionUID = 1L;

        public IncompleteReplayException(String gateway, List<String> ordering,
                                         long fetched, long expected) {
            super(gateway + ": replayed " + fetched + " of " + expected + " rows. The ordering tuple "
                  + ordering + " is probably not unique, so rows sharing those values were skipped;"
                  + " rows deleted during the replay would also do this. Refusing to report a partial"
                  + " replay as a complete one.");
        }
    }

    /** The ordering tuple's values on the current row: the resume point for the next page. */
    private List<Object> currentOrderingValues(ResultSet rs) throws SQLException {
        List<Object> values = new ArrayList<>(binding.orderingColumns().size());
        for (String column : binding.orderingColumns()) {
            values.add(rs.getObject(column));
        }
        return values;
    }

    /** Apply the key filter and hand the event to its subscriber. */
    private void publishDownstream(P payload) {
        if (!keys.isEmpty() && !keys.contains(payload.getDatumKey())) {
            return;
        }
        rowsRead++;
        Gateway downstream = subscriberForKey(topic, payload.getDatumKey());
        if (downstream != null) {
            downstream.onEvent(topic, payload);
        }
    }

    // ------------------------------------------------------------------
    // Gateway contract
    // ------------------------------------------------------------------

    /** A source does not receive events; see §1 on why this is a separate class from the sink. */
    @Override
    public <Q extends Datum> void onEvent(Topic<Q> topic, Q payload) {
        throw new UnsupportedOperationException(name() + ": SqlSourceGateway does not receive events");
    }

    @Override
    @SuppressWarnings("unchecked")
    public <Q extends Datum> void publish(Topic<Q> topic, Q payload) {
        if (!topic.equals(this.topic)) {
            throw new IllegalArgumentException(name() + ": unknown topic " + topic.name());
        }
        publishDownstream((P) payload);
    }

    // ------------------------------------------------------------------
    // Reporting
    // ------------------------------------------------------------------

    /** The upper bound fixed at startup: the last event time this replay will consider. */
    public long upperBound() { return upperBound; }

    /** Rows the window contained when the bound was fixed, for detecting a changed dataset. */
    public long rowsInWindow() { return rowsInWindow; }

    /** Rows actually published (after the key filter). */
    public long rowsRead() { return rowsRead; }

    /** Rows fetched from the table, before the key filter: what the completeness check compares. */
    public long rowsFetched() { return rowsFetched; }

    /** Pages fetched, for the manifest and for sizing. */
    public long pagesRead() { return pagesRead; }

    /** Whether the replay ran under a database snapshot, or relied on the bound alone. */
    public boolean usedSnapshot() { return usedSnapshot; }

    /** The table this source reads. */
    public SqlTableBinding binding() { return binding; }
}
