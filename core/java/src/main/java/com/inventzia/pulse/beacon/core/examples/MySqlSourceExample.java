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
package com.inventzia.pulse.beacon.core.examples;

import com.inventzia.pulse.beacon.core.GatewayStatus;
import com.inventzia.pulse.beacon.core.MultiClientEngine;
import com.inventzia.pulse.beacon.core.RunListener;
import com.inventzia.pulse.beacon.core.RunOutcome;
import com.inventzia.pulse.beacon.core.Topic;
import com.inventzia.pulse.beacon.core.gateway.database.SqlSourceGateway;
import com.inventzia.pulse.beacon.core.gateway.database.SqlTableBinding;
import com.inventzia.pulse.beacon.core.run.RunLayout;
import com.inventzia.pulse.beacon.core.run.RunRecording;
import com.inventzia.pulse.data.schemas.marketdata.CdfBar;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runnable example: replay a SQL table back into the engine through {@link SqlSourceGateway}.
 *
 * <p>The counterpart to {@link MySqlSinkExample}. The rows that example wrote are read back in
 * event-time order, paged by keyset, and dispatched to an actor exactly as any historical source would
 * be — so what went into the database comes back out as events.
 *
 * <h2>Running it</h2>
 * <pre>
 *   export PULSE_DB_URL='jdbc:mysql://&lt;host&gt;:3306/&lt;database&gt;'
 *   export PULSE_DB_USER='&lt;user&gt;'
 *   export PULSE_DB_PASSWORD='&lt;password&gt;'
 *   java ... MySqlSourceExample [pageSize] [outputRoot]
 * </pre>
 *
 * <p>Credentials may equally come from {@code ~/.pulse/db.properties}; see {@link SqlExampleDatabase}.
 *
 * <h2>The printing consumer, and why it is optional</h2>
 * <p>{@link PrintConsumer} logs every event it receives. That is the point of it — but a consumer runs
 * on the engine's <em>dispatch thread</em>, so logging one line per event throttles the replay to
 * however fast the console can take it. Measured on this example: 777 bars took ~1s with the consumer
 * off and ~32s with it on against a slow console, all of the difference being the terminal rather than
 * the engine or the database. So it is a flag, defaulting to off, and every bar is in {@code
 * events.jsonl} either way.
 *
 * <h2>What it demonstrates</h2>
 * <ul>
 *   <li><b>Read-only is enough.</b> Nothing here provisions or writes; a source runs against a table
 *       someone else prepared, with read-only credentials (§1).</li>
 *   <li><b>Reproducibility.</b> A verified-unique ordering tuple, keyset paging rather than
 *       {@code OFFSET}, and an upper bound fixed at startup so rows appended mid-replay cannot leak
 *       in (§2, §3). Run it twice and the order is identical.</li>
 *   <li><b>A replay that skipped rows fails.</b> The row count measured at startup is compared with
 *       what was actually read, so a partial replay cannot pass as a complete one (§2).</li>
 *   <li><b>Failure reaches the engine.</b> The source is declared fatal, so a mid-replay failure ends
 *       the run as {@code failed} with the gateway named, rather than completing on partial
 *       history (§8).</li>
 * </ul>
 */
public final class MySqlSourceExample {

    /** Names the run directory under the output root. */
    private static final String APP = "MySqlSourceExample";

    /** Recorder queue: generous, so a slow disk drops nothing on a 773-bar replay. */
    private static final int RECORDER_CAPACITY = 1 << 16;

    /** The replay window starts at the epoch: a historical replay considers everything it finds. */
    private static final long START = 0L;

    /**
     * The replay window's end.
     *
     * <p><b>It must be in the past, and that is not a detail.</b> A gateway picks its operating mode as
     * {@code now > endTime ? COMPRESSED_TIME : REAL_TIME}, so a window ending in the future puts the
     * engine into live mode — where dispatch is arrival-ordered by contract rather than time-ordered
     * through the TimeMachine. A historical replay needs compressed time: that is what makes the
     * ordering deterministic across sources, and what the reproducibility argument in
     * {@code docs/pulse-sql-gateway.md} §2 rests on. With one source it happens to look right anyway,
     * which is exactly why it is worth stating.
     *
     * <p>"Everything up to the moment this run started" is the honest window for a historical replay,
     * and it is unambiguously in the past.
     */
    private static long historicalWindowEnd() {
        return System.currentTimeMillis() - 1;
    }

    public static void main(String[] args) throws Exception {

        // ---------------------------------------------------------
        // 0) Some constants for this example
        // ---------------------------------------------------------
        // The replay window: wide, and ending in the past so the engine runs in compressed time.
        final long startTime = START;
        final long endTime   = historicalWindowEnd();

        // Whether to attach the printing consumer. Off by default: it logs one line per event on the
        // dispatch thread, which throttles the replay to the speed of the console (see the class note).
        // $PULSE_PRINT_CONSUMER=true turns it on; every bar is recorded to events.jsonl regardless.
        final boolean runPrintConsumer = Boolean.parseBoolean(envOr("PULSE_PRINT_CONSUMER", "false"));

        // ---------------------------------------------------------
        // 1) Arguments
        // ---------------------------------------------------------
        // a) the page size - deliberately small by default so the keyset paging is visible in the
        //    output; a real replay would use hundreds or thousands
        int pageSize = args.length > 0 ? Integer.parseInt(args[0]) : 200;

        // b) the output root. An explicit root beats $PULSE_OUTPUT, which beats ~/.pulse/runs. Passing
        //    it as an argument is usually easier than adding an environment variable to an IDE launch.
        Path outputRoot = args.length > 1 && !args[1].isBlank() ? Path.of(args[1].trim()) : null;

        // ---------------------------------------------------------
        // 2) Create the loggers
        // ---------------------------------------------------------
        // Nothing to create. Components log through their own ComponentReporter, configured by
        // src/main/resources/logback.xml rather than by handing each component a logger. Run-scoped
        // threads additionally tee their output into this run's console.log.

        // ---------------------------------------------------------
        // 3) Connect: where the database is, and which dialect speaks to it
        // ---------------------------------------------------------
        // Read-only is enough for everything below. Nothing here provisions or writes: a source runs
        // against a table someone else prepared (§1).
        SqlExampleDatabase db = SqlExampleDatabase.fromEnvironment();

        System.out.println("sql source -> pulse");
        System.out.println("  " + db.describe());

        // ---------------------------------------------------------
        // 4) Create the storage binding - the contract between the table and the datum type
        // ---------------------------------------------------------
        // The same tuple the sink wrote under, and it must be verifiably unique or the replay would
        // skip rows that share a timestamp (§2).
        SqlTableBinding binding = db.bindingFor(CdfBar.class, "symb");
        System.out.println("  type     " + binding.mapping().typeId());
        System.out.println("  order    " + binding.orderingColumns());
        System.out.println("  pageSize " + pageSize + "  (small on purpose, to show paging)");
        System.out.println("  consumer " + (runPrintConsumer
                ? "on   ($PULSE_PRINT_CONSUMER) - logs every bar, and throttles the replay"
                : "off  ($PULSE_PRINT_CONSUMER=true to attach it); bars still go to events.jsonl"));

        // ---------------------------------------------------------
        // 5) Create the engine - the core of the infrastructure
        // ---------------------------------------------------------
        MultiClientEngine engine = new MultiClientEngine("source-example", startTime, endTime);

        // ---------------------------------------------------------
        // 6) Create the topics and the list of keys for each topic
        // ---------------------------------------------------------
        // a) the market-data topic the rows are replayed onto
        Topic<CdfBar> topicBars = new Topic<>("market.data.bar", CdfBar.class);

        // b) its keys, taken from the table itself - routing is per key and one-to-one, so each key
        //    actually present has to be registered for both the source and the actor
        List<String> keysBars = distinctKeys(db, binding);
        if (keysBars.isEmpty()) {
            System.out.println();
            System.out.println("the table is empty - run MySqlSinkExample first");
            System.exit(2);
        }
        System.out.println("  keys     " + keysBars);

        // ---------------------------------------------------------
        // 7) Create the clients
        // ---------------------------------------------------------
        // The generic printing consumer, created only if it is wanted. It is an actor rather than a
        // gateway, which is also why the recorder can subscribe to the same route: subscriber routing
        // is one-to-one for gateways, but actors do not compete for that slot.
        PrintConsumer clientPrinter = runPrintConsumer ? new PrintConsumer("printer") : null;

        // A run listener, so we can report the engine's own verdict rather than infer it.
        AtomicReference<RunOutcome> outcome = new AtomicReference<>();
        engine.addRunListener(new RunListener() {
            @Override public void onRunTerminated(RunOutcome o) { outcome.set(o); }
        });

        // ---------------------------------------------------------
        // 8) Create the gateways
        // ---------------------------------------------------------
        // The SQL source: a publisher, and it drives the clock. Reproducibility rests on three things
        // it does - a verified-unique ordering tuple, keyset paging rather than OFFSET, and an upper
        // bound fixed at startup so rows appended mid-replay cannot leak in (§2, §3).
        SqlSourceGateway<CdfBar> sourceGateway = new SqlSourceGateway<>(
                "SqlSource", db.dataSource(), binding, db.dialect(), topicBars, keysBars,
                startTime, endTime, pageSize);

        // A replay that stops halfway must fail the run: a result derived from partial history is worse
        // than no result, and nothing downstream could tell the difference (§8).
        sourceGateway.setFailureIsFatal(true);

        // ---------------------------------------------------------
        // 9) Validate before the run
        // ---------------------------------------------------------
        // Read-only: registered, ready, readable, and with a tuple a declared constraint proves unique.
        sourceGateway.validate();

        // ---------------------------------------------------------
        // 10) Start recording the run
        // ---------------------------------------------------------
        // Opens $PULSE_OUTPUT/historical/MySqlSourceExample/<runId>/ immediately. Every bar replayed out
        // of the table is written to events.jsonl, so the recording is a faithful copy of what the
        // database produced - which is what makes the replay checkable after the fact, and what lets the
        // printing consumer stay optional.
        try (RunRecording run = RunRecording.start(APP, engine, startTime, endTime,
                "sql:" + binding.table().qualified(), RECORDER_CAPACITY, outputRoot)) {

            // a) record the data route
            run.recordRoute(engine, topicBars, keysBars);

            // b) record the lifecycle of the engine and the source gateway
            run.recordStatus(engine, sourceGateway);

            // ---------------------------------------------------------
            // 11) Perform the registration of each client with its subscribed topic(s)
            // ---------------------------------------------------------
            // the printer subscribes to the bars; it publishes nothing
            if (clientPrinter != null) {
                engine.registerActor(clientPrinter, Map.of(topicBars, keysBars), Map.of());
            }

            // ---------------------------------------------------------
            // 12) Perform the registrations between engine and gateways
            // ---------------------------------------------------------
            engine.registerPublisher(sourceGateway, topicBars, keysBars);

            // ---------------------------------------------------------
            // 13) Start the engine, then the gateways
            // ---------------------------------------------------------
            // The engine first, and we wait for STARTED: a source that published into an engine which
            // was not yet dispatching would have its early events dropped. Threads are run-scoped so
            // their logging lands in this run's console.log.
            System.out.println();
            Thread engineThread = run.scoped(engine, "engine");
            engineThread.start();
            RunUtils.awaitStatus(engine, GatewayStatus.STARTED, 5_000);

            RunLayout.RunPaths paths = run.paths();
            System.out.println("  run    " + (paths == null ? "(not recording)" : paths.dir()));

            Thread sourceThread = run.scoped(sourceGateway, "sql-source");
            sourceThread.start();

            // ---------------------------------------------------------
            // 14) Wait for the run to finish
            // ---------------------------------------------------------
            engineThread.join();
        }   // closing the recording writes the trailer and the final run.json status

        // ---------------------------------------------------------
        // 15) Report the replay
        // ---------------------------------------------------------
        // rowsFetched against rowsInWindow is the completeness check: a replay that skipped rows must
        // not pass as a complete one (§2).
        System.out.println();
        System.out.println("replay");
        System.out.println("  rows in window  " + sourceGateway.rowsInWindow());
        System.out.println("  rows read       " + sourceGateway.rowsRead());
        System.out.println("  rows fetched    " + sourceGateway.rowsFetched());
        System.out.println("  pages           " + sourceGateway.pagesRead());
        System.out.println("  upper bound     " + sourceGateway.upperBound()
                           + "  (fixed at startup; later writes cannot leak in)");
        System.out.println("  snapshot        " + sourceGateway.usedSnapshot());
        RunOutcome o = outcome.get();
        System.out.println("  run status      " + (o == null ? "unknown" : o.describe()));

        if (o == null || !o.completed()) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** The distinct business keys present, so the source and the actor can be routed for each. */
    private static List<String> distinctKeys(SqlExampleDatabase db, SqlTableBinding binding)
            throws Exception {
        String keyColumn = binding.mapping().keyColumn().columnName();
        String sql = "SELECT DISTINCT " + db.dialect().quote(keyColumn)
                   + " FROM " + db.dialect().qualify(binding.table());
        List<String> keys = new ArrayList<>();
        try (Connection c = db.dataSource().getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                keys.add(rs.getString(1));
            }
        }
        return keys;
    }

    private static String envOr(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }

    private MySqlSourceExample() { }
}
