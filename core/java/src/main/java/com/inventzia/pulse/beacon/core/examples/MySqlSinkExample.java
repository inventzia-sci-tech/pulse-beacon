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
import com.inventzia.pulse.beacon.core.Topic;
import com.inventzia.pulse.beacon.core.gateway.database.DeliveryAccounting;
import com.inventzia.pulse.beacon.core.gateway.database.SinkFailurePolicy;
import com.inventzia.pulse.beacon.core.gateway.database.SqlSinkGateway;
import com.inventzia.pulse.beacon.core.gateway.database.SqlTableBinding;
import com.inventzia.pulse.beacon.core.gateway.database.WriteMode;
import com.inventzia.pulse.beacon.core.AbstractGateway;
import com.inventzia.pulse.beacon.core.gateway.file.CsvReaderGateway;
import com.inventzia.pulse.beacon.core.gateway.file.JsonlReaderGateway;
import com.inventzia.pulse.beacon.core.run.RunLayout;
import com.inventzia.pulse.beacon.core.run.RunRecording;
import com.inventzia.pulse.data.schemas.marketdata.CdfBar;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Runnable example: stream a data file into a SQL database through {@link SqlSinkGateway}.
 *
 * <p>A JSONL file of {@link CdfBar} records is replayed by a {@link JsonlReaderGateway} in compressed
 * time; the engine dispatches each bar to a SQL sink, which batches them into transactions and accounts
 * for every one. The companion {@link MySqlSourceExample} reads them back out.
 *
 * <h2>Running it</h2>
 * <pre>
 *   export PULSE_DB_URL='jdbc:mysql://&lt;host&gt;:3306/&lt;database&gt;'
 *   export PULSE_DB_USER='&lt;user&gt;'
 *   export PULSE_DB_PASSWORD='&lt;password&gt;'
 *   java ... MySqlSinkExample [path/to/bars.jsonl]
 * </pre>
 *
 * <p>Two input formats are accepted, chosen by extension:
 * <ul>
 *   <li><b>{@code .jsonl}</b> — one JSON object per line with {@code CdfBar}'s fields.</li>
 *   <li><b>{@code .csv}</b> — a header row of {@code date,datetime,op,hi,lo,cl,vwap,count,vlm,symb},
 *       US-style {@code M/d/yyyy} dates. Because the file's {@code datetime} carries <b>no time
 *       zone</b> and the routing time is an absolute instant, the zone must be supplied: it defaults
 *       to UTC and can be set with {@code $PULSE_CSV_ZONE} (e.g. {@code America/New_York}). The zone
 *       moves every event by whole hours, so the example prints the one it used.</li>
 * </ul>
 *
 * <p>With no argument it uses the bundled four-bar JSONL sample, so the example runs before you have a
 * file of your own.
 *
 * <h2>What it demonstrates</h2>
 * <ul>
 *   <li><b>Provisioning is a separate, privileged act.</b> The table is created and registered once,
 *       as a state machine, because MySQL commits {@code CREATE TABLE} implicitly (§5).</li>
 *   <li><b>Startup validation.</b> The sink checks the table before the run, so an extra {@code NOT
 *       NULL} column fails here rather than on the first insert, mid-run (§5).</li>
 *   <li><b>Three-outcome accounting.</b> Every accepted bar ends as committed, not-committed, unknown,
 *       overflow, a serialization error, or abandoned. The books must close (§7).</li>
 *   <li><b>An essential sink fails the run.</b> The database <em>is</em> this example's output, so a
 *       persistence failure is not something to report as a success (§7, §8).</li>
 * </ul>
 */
public final class MySqlSinkExample {

    private static final String SAMPLE = "/examples/data/cdf_bars.jsonl";

    /** Names the run directory under the output root. */
    private static final String APP = "MySqlSinkExample";

    /** Recorder queue: generous, so a slow disk drops nothing on a 773-bar load. */
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


    /** Generous, because a hosted database is a network round trip away per batch. */
    private static final long DRAIN_TIMEOUT_MILLIS = 300_000;

    public static void main(String[] args) throws Exception {

        // ---------------------------------------------------------
        // 0) Some constants for this example
        // ---------------------------------------------------------
        // The replay window. Wide enough for any plausible input file, and finite rather than
        // Long.MAX_VALUE so the engine's shutdown signal (enqueued at endTime+1) cannot overflow.
        final long startTime = START;
        final long endTime   = historicalWindowEnd();

        // Daily bars arrive one per symbol per day, so a small batch keeps the example legible while
        // still exercising more than one transaction.
        final int  batchSize = 500;

        // ---------------------------------------------------------
        // 1) Arguments
        // ---------------------------------------------------------
        // Optional: the data file. With none we use the bundled four-bar JSONL sample, so the example
        // runs before you have a file of your own. A .csv extension selects the CSV reader below.
        Path input = args.length > 0 ? Path.of(args[0]) : RunUtils.resource(SAMPLE);
        if (!Files.isReadable(input)) {
            System.err.println("cannot read " + input);
            System.exit(2);
        }
        boolean csv = input.getFileName().toString().toLowerCase().endsWith(".csv");

        // An explicit root beats $PULSE_OUTPUT, which beats ~/.pulse/runs. Passing it as an argument is
        // usually easier than adding an environment variable to an IDE launch configuration.
        Path outputRoot = args.length > 1 && !args[1].isBlank() ? Path.of(args[1].trim()) : null;

        // ---------------------------------------------------------
        // 2) Create the loggers
        // ---------------------------------------------------------
        // Nothing to create. Components log through their own ComponentReporter, configured by
        // src/main/resources/logback.xml rather than by handing each component a logger. Named
        // appenders per component are set up there if you want them separated.

        // ---------------------------------------------------------
        // 3) Connect: where the database is, and which dialect speaks to it
        // ---------------------------------------------------------
        // Credentials come from the environment or ~/.pulse/db.properties - never from arguments.
        // The dialect is chosen from the JDBC URL scheme, because getting it wrong is not cosmetic:
        // it decides identifier quoting, the paging clause and the column types the DDL declares.
        SqlExampleDatabase db = SqlExampleDatabase.fromEnvironment();

        System.out.println("pulse -> sql sink");
        System.out.println("  " + db.describe());
        System.out.println("  input  " + input + "  (" + (csv ? "csv" : "jsonl") + ")");

        // ---------------------------------------------------------
        // 4) Create the storage binding - the contract between the datum type and the table
        // ---------------------------------------------------------
        // The ordering tuple is event time, then the business key, then the ingestion id. That last
        // column is what actually makes the tuple unique: one symbol can legitimately produce several
        // bars at the same timestamp, and a tuple that can repeat makes keyset paging skip rows
        // silently (docs/pulse-sql-gateway.md section 2).
        SqlTableBinding binding = db.bindingFor(CdfBar.class, "symb");
        System.out.println("  type   " + binding.mapping().typeId());
        System.out.println("  order  " + binding.orderingColumns());

        // ---------------------------------------------------------
        // 5) Provision the table - a separate, privileged act
        // ---------------------------------------------------------
        // Ordinary startup needs only read rights against a table someone already provisioned
        // (section 1). This is the part that needs DDL rights, so it is called out rather than hidden
        // inside the gateway. It also resolves a previous run that died mid-provisioning, which is a
        // recoverable state rather than a corruption (section 5).
        boolean created = db.provisionIfAbsent(binding);
        System.out.println("  table  " + (created ? "created" : "already provisioned"));

        // ---------------------------------------------------------
        // 6) Create the engine - the core of the infrastructure
        // ---------------------------------------------------------
        MultiClientEngine engine = new MultiClientEngine("sink-example", startTime, endTime);

        // ---------------------------------------------------------
        // 7) Create the topics and the list of keys for each topic
        // ---------------------------------------------------------
        // a) the market-data topic the file is replayed onto
        Topic<CdfBar> topicBars = new Topic<>("market.data.bar", CdfBar.class);

        // b) its keys, read from the file itself. Subscriber routing is per key and one-to-one, so
        //    every key present in the data must be registered - an empty list routes nothing at all.
        List<String> keysBars = csv ? csvKeys(input) : jsonlKeys(input);
        if (keysBars.isEmpty()) {
            System.err.println("no rows with a symbol found in " + input);
            System.exit(2);
        }
        System.out.println("  keys   " + keysBars);

        // ---------------------------------------------------------
        // 8) Create the clients
        // ---------------------------------------------------------
        // None in this example. The database is the only consumer, and it is a gateway rather than an
        // actor. MySqlSourceExample is where a client appears, reading these rows back.

        // ---------------------------------------------------------
        // 9) Create the gateways
        // ---------------------------------------------------------
        // a) the file reader - the source, which drives the clock
        AbstractGateway readerGateway;
        if (csv) {
            // The file's datetime column carries no zone, and the routing time is an absolute instant,
            // so a zone has to be supplied. It moves every event by whole hours and can shift a bar to
            // another calendar day, which is why the example prints the one it used.
            ZoneId zone = ZoneId.of(envOr("PULSE_CSV_ZONE", "UTC"));
            System.out.println("  zone   " + zone + "  (the file's datetime has none; $PULSE_CSV_ZONE)");
            readerGateway = new CsvReaderGateway<>("CsvReader", topicBars, keysBars, input,
                    new CdfBarCsvMapper(zone), startTime, endTime);
        } else {
            readerGateway = new JsonlReaderGateway<>("FileReader", topicBars, keysBars, input,
                    startTime, endTime);
        }
        // A failing source must fail the run: a partial load is worse than none, because nothing
        // downstream can tell a half-loaded table from a complete one (section 8).
        readerGateway.setFailureIsFatal(true);

        // b) the SQL sink - the subscriber, which never drives the clock
        SqlSinkGateway sinkGateway = new SqlSinkGateway("SqlSink", db.dataSource(), binding,
                db.dialect(), runId(), SinkFailurePolicy.ESSENTIAL, startTime, endTime,
                SqlSinkGateway.DEFAULT_QUEUE_CAPACITY, batchSize,
                SqlSinkGateway.DEFAULT_LINGER_MILLIS);

        // Converge rather than append: loading this file again must leave the same rows, not twice as
        // many. The business key plus the event time identifies a daily bar exactly, which is the case
        // the natural-key upsert exists for (section 7).
        sinkGateway.setWriteMode(WriteMode.UPSERT_ON_NATURAL_KEY);
        System.out.println("  write  " + sinkGateway.writeMode() + "  (re-running will not duplicate)");

        // A hosted database is a network round trip away per batch, so the final drain takes far longer
        // than against a local engine. The default is tuned for local; measured against a hosted MySQL,
        // 773 rows took ~30s before batch rewriting was enabled - exactly the failure this guards.
        sinkGateway.setDrainTimeoutMillis(DRAIN_TIMEOUT_MILLIS);

        // ---------------------------------------------------------
        // 10) Validate before the run, not on the first write
        // ---------------------------------------------------------
        // An extra NOT NULL column with no default reads perfectly and makes every insert fail; a
        // too-narrow decimal column rounds silently. Both are caught here, where the failure looks
        // like the configuration problem it is rather than a data problem mid-run (section 5).
        sinkGateway.validate();

        // ---------------------------------------------------------
        // 11) Start recording the run
        // ---------------------------------------------------------
        // Opens $PULSE_OUTPUT/historical/MySqlSinkExample/<runId>/ immediately, with run.json marked
        // running until the run ends. "historical" rather than "live" because the window ends in the
        // past, which is what puts the engine in compressed time (see historicalWindowEnd).
        try (RunRecording run = RunRecording.start(APP, engine, startTime, endTime,
                "file:" + input.getFileName(), RECORDER_CAPACITY, outputRoot)) {

            // The data route is deliberately NOT recorded here, and it is not an oversight.
            //
            // Subscriber routing is one-to-one: a topic+key has exactly one subscriber gateway. On this
            // route that gateway is the SQL sink, so asking the recorder to take it as well is refused
            // by the engine (it is the Stage A limitation noted in RealTimeEchoExample). The source
            // example records its data route precisely because its consumer is an *actor*, and actors
            // do not contend for the slot.
            //
            // Nothing is actually lost: this run's events are being written to the database, which is
            // a more durable record of them than events.jsonl would be. What the recording adds here is
            // the run's manifest and its lifecycle.
            //
            // Record the lifecycle of the engine and both gateways. A reader of the recording can then
            // see the reader or the sink fail without going to the log.
            run.recordStatus(engine, readerGateway, sinkGateway);

            // ---------------------------------------------------------
            // 12) Perform the registrations between engine and gateways
            // ---------------------------------------------------------
            // the file reader publishes the bars...
            engine.registerPublisher(readerGateway, topicBars, keysBars);
            // ...and the SQL sink subscribes to them
            engine.registerSubscriber(sinkGateway, topicBars, keysBars);

            // ---------------------------------------------------------
            // 13) Start the gateways
            // ---------------------------------------------------------
            // The engine goes first and we wait for STARTED, so the gateways do not publish into an
            // engine that is not yet dispatching. Threads are run-scoped, so everything they log lands
            // in this run's console.log rather than only on the terminal.
            Thread engineThread = run.scoped(engine, "engine");
            engineThread.start();
            RunUtils.awaitStatus(engine, GatewayStatus.STARTED, 5_000);

            RunLayout.RunPaths paths = run.paths();
            System.out.println("  run    " + (paths == null ? "(not recording)" : paths.dir()));

            // The sink's writer thread is its own: the engine's shutdown barrier waits for it to drain,
            // which is what lets a failure in the closing writes still fail the run.
            Thread writerThread = run.scoped(sinkGateway, "sql-writer");
            writerThread.start();

            Thread readerThread = run.scoped(readerGateway, "file-reader");
            readerThread.start();

            // ---------------------------------------------------------
            // 14) Wait for the run to finish
            // ---------------------------------------------------------
            engineThread.join();
            writerThread.join(60_000);
        }   // closing the recording writes the trailer and the final run.json status

        // ---------------------------------------------------------
        // 15) Report the delivery accounting
        // ---------------------------------------------------------
        // Every accepted event ends in exactly one of these, and the books must close: "in flight" is
        // not an outcome once the writer has stopped (section 7).
        DeliveryAccounting.Counts c = sinkGateway.counts();
        System.out.println();
        System.out.println("delivery");
        System.out.println("  observed            " + c.observed());
        System.out.println("  committed           " + c.committed());
        System.out.println("  notCommitted        " + c.notCommitted());
        System.out.println("  unknown             " + c.unknown());
        System.out.println("  overflow            " + c.overflow());
        System.out.println("  serializationErrors " + c.serializationErrors());
        System.out.println("  abandoned           " + c.abandoned());
        System.out.println("  books closed        " + c.isFinal());
        System.out.println("  engine status       " + engine.status());

        if (!c.isFinal() || c.hasFailures() || sinkGateway.hasFailed()) {
            System.out.println();
            System.out.println("the load did not complete cleanly; see above");
            System.exit(1);
        }
        System.out.println();
        System.out.println("loaded " + c.committed() + " bars into " + binding.table().qualified()
                           + " (" + rowCount(db, binding) + " rows in the table)");
    }

    /**
     * The distinct datum keys in a JSONL file.
     *
     * <p>Subscriber routing is per key and one-to-one, so both the reader and the sink have to be
     * registered for every key the file actually contains — an empty list routes nothing at all.
     */
    private static List<String> jsonlKeys(Path file) throws Exception {
        try (var lines = Files.lines(file)) {
            return lines.filter(l -> !l.isBlank())
                    .map(MySqlSinkExample::symbolOf)
                    .filter(s -> s != null)
                    .distinct()
                    .toList();
        }
    }

    /** The distinct values of the {@code symb} column in a CSV file. */
    private static List<String> csvKeys(Path file) throws Exception {
        try (var reader = Files.newBufferedReader(file)) {
            String header = reader.readLine();
            if (header == null) return List.of();
            int col = CsvReaderGateway.parseLine(header).indexOf("symb");
            if (col < 0) {
                throw new IllegalStateException(file + ": no 'symb' column in the header row");
            }
            Set<String> keys = new LinkedHashSet<>();
            String line;
            while ((line = reader.readLine()) != null) {
                List<String> values = CsvReaderGateway.parseLine(line);
                if (col < values.size() && !values.get(col).isBlank()) {
                    keys.add(values.get(col));
                }
            }
            return List.copyOf(keys);
        }
    }

    private static String envOr(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }

    /** Pull the {@code symb} value out of a line without building the whole datum. */
    private static String symbolOf(String jsonLine) {
        int i = jsonLine.indexOf("\"symb\"");
        if (i < 0) return null;
        int open = jsonLine.indexOf('"', jsonLine.indexOf(':', i) + 1);
        int close = jsonLine.indexOf('"', open + 1);
        return open < 0 || close < 0 ? null : jsonLine.substring(open + 1, close);
    }

    /** The table's current row count, so a re-run visibly does not grow it. */
    private static long rowCount(SqlExampleDatabase db, SqlTableBinding binding) {
        String sql = "SELECT COUNT(*) FROM " + db.dialect().qualify(binding.table());
        try (var c = db.dataSource().getConnection();
             var st = c.createStatement();
             var rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private static String runId() {
        return java.time.Instant.now().toString().replace(':', '-') + "-sink";
    }

    private MySqlSinkExample() { }
}
