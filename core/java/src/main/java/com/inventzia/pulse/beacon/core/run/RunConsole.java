/*
 * SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
 * Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.
 *
 * This file is part of pulse-beacon.
 *
 * pulse-beacon is dual-licensed:
 *   - Under the GNU Affero General Public License v3.0 or later (see LICENSE-AGPL-3.0).
 *   - Under a commercial license (see LICENSE-COMMERCIAL.txt).
 *     Contact operations@inventzia.com.
 */
package com.inventzia.pulse.beacon.core.run;

import com.inventzia.pulse.beacon.core.ReportLevel;
import com.inventzia.pulse.beacon.core.Slf4jReporter;

import org.slf4j.MDC;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Objects;

/**
 * Per-run {@code console.log} capture, isolated by run identity.
 *
 * <p>The engine and its gateways and actors all log through pulse-beacon's own reporting stream, so
 * capturing a run's console does not need a logging backend at all: this attaches a {@link
 * com.inventzia.pulse.beacon.core.ReportTap} to the shared reporter and writes each record to the
 * run's file. Isolation is by the run-id carried in the SLF4J {@link MDC} under {@link
 * RunLayout#MDC_RUN_ID}: the tap writes a record only when the reporting thread's MDC run-id matches
 * this console's run-id. Concurrent runs each attach their own console; a record therefore lands only
 * in the file of the run whose identity its thread carries, never in another's.
 *
 * <p>The orchestration must propagate that identity onto every thread that logs for the run (the
 * engine thread, source-gateway threads, the recorder thread, and any bridge threads) — see {@link
 * RunRecording#scoped}. A record logged on a thread with no run-id in its MDC is captured by no
 * console (it still reaches the normal reporter sink). This is a Logback-free, self-contained capture;
 * it records the reporting stream, not raw {@code System.out} writes.
 */
public final class RunConsole implements AutoCloseable {

    private final String  runId;
    private final Path    file;
    private final BufferedWriter writer;
    private final Slf4jReporter reporter;
    private final com.inventzia.pulse.beacon.core.ReportTap tap;
    private volatile boolean closed = false;

    private RunConsole(String runId, Path file, BufferedWriter writer, Slf4jReporter reporter) {
        this.runId    = runId;
        this.file     = file;
        this.writer   = writer;
        this.reporter = reporter;
        this.tap      = this::onReport;
    }

    /**
     * Open {@code consoleLog} and start capturing this run's reporting stream to it. Attach on the
     * shared reporter; detach and close with {@link #close()} (idempotent) at run end.
     *
     * @param runId      the run identity to match against the reporting thread's MDC
     * @param consoleLog the file to write (created, truncated)
     * @return an open capture; close it to stop and release the file
     */
    public static RunConsole attach(String runId, Path consoleLog) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(consoleLog, "consoleLog");
        BufferedWriter w;
        try {
            Files.createDirectories(consoleLog.getParent());
            w = Files.newBufferedWriter(consoleLog,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to open run console log: " + consoleLog, e);
        }
        RunConsole console = new RunConsole(runId, consoleLog, w, Slf4jReporter.shared());
        console.reporter.addTap(console.tap);
        return console;
    }

    private void onReport(long timestamp, String source, String message, ReportLevel level) {
        if (closed) return;
        if (!runId.equals(MDC.get(RunLayout.MDC_RUN_ID))) return;   // not this run's thread
        String line = Instant.ofEpochMilli(timestamp) + " " + pad(level) + " [" + source + "] " + message;
        synchronized (writer) {
            if (closed) return;
            try {
                writer.write(line);
                writer.newLine();
                writer.flush();
            } catch (IOException e) {
                // Best-effort: a console-log write failure must never disturb the run. Detach so we
                // stop trying, and surface it once on the normal stream.
                closed = true;
                reporter.removeTap(tap);
                reporter.report(System.currentTimeMillis(), "RunConsole",
                        "failed writing run console log " + file + "; stopping capture: " + e,
                        ReportLevel.SEVERE);
            }
        }
    }

    private static String pad(ReportLevel level) {
        String s = level.name();
        return s.length() >= 8 ? s : s + " ".repeat(8 - s.length());
    }

    /** Detach from the reporter, flush, and close the file. Idempotent. */
    @Override
    public void close() {
        synchronized (writer) {
            if (closed) return;
            closed = true;
        }
        reporter.removeTap(tap);
        synchronized (writer) {
            try {
                writer.flush();
                writer.close();
            } catch (IOException e) {
                reporter.report(System.currentTimeMillis(), "RunConsole",
                        "failed closing run console log " + file + ": " + e, ReportLevel.SEVERE);
            }
        }
    }
}
