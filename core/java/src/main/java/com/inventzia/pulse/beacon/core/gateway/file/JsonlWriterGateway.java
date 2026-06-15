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
package com.inventzia.pulse.beacon.core.gateway.file;

import com.inventzia.pulse.beacon.core.AbstractGateway;
import com.inventzia.pulse.beacon.core.GatewayStatus;
import com.inventzia.pulse.beacon.core.Topic;
import com.inventzia.pulse.data.datum.Datum;
import com.inventzia.pulse.data.datum.DatumCodec;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;

/**
 * A gateway that subscribes to events on the bus and persists each one as a
 * JSONL (JSON Lines) record — one JSON object per line — to a file.
 *
 * <p>Writing is driven by the engine's dispatch thread via {@link #onEvent};
 * no separate I/O loop is needed. The gateway's own {@link #run()} method
 * initialises, opens the file, and parks until {@link #disconnect()} is called
 * (e.g. at engine shutdown), at which point the file is flushed and closed.
 *
 * <h2>Append vs overwrite</h2>
 * <p>Default behaviour is to overwrite the target file on each run.
 * Pass {@code append = true} to accumulate records across multiple sessions.
 *
 * <h2>Flush policy</h2>
 * <p>The underlying {@link BufferedWriter} is flushed every
 * {@code flushEvery} events (default: {@value #DEFAULT_FLUSH_EVERY}).
 * The file is always fully flushed and closed when {@link #disconnect()} is
 * called regardless of this setting.
 *
 * <h2>Thread safety</h2>
 * <p>{@link #onEvent} runs on the engine dispatch thread and is the only
 * writer. {@link #disconnect} may arrive from a different thread; it is
 * synchronised on {@code this} so it waits for any in-progress write to
 * complete before closing the file.
 */
public final class JsonlWriterGateway extends AbstractGateway {

    static final int DEFAULT_FLUSH_EVERY = 100;

    private final Path         filePath;
    private final DatumCodec   codec = DatumCodec.instance();
    private final boolean      append;
    private final int          flushEvery;

    private BufferedWriter       writer;
    private int                  writeCount;
    private final CountDownLatch stopLatch = new CountDownLatch(1);

    /**
     * Creates a writer that overwrites the target file on each run and flushes
     * every {@value #DEFAULT_FLUSH_EVERY} events.
     */
    public JsonlWriterGateway(String name,
                              Path filePath,
                              long startTime,
                              long endTime) {
        this(name, filePath, startTime, endTime, false, DEFAULT_FLUSH_EVERY);
    }

    /**
     * @param name       human-readable name for this gateway instance
     * @param filePath   path to the JSONL output file
     * @param startTime  epoch millis for the start of this gateway's window
     * @param endTime    epoch millis for the end of this gateway's window
     * @param append     {@code true} to append to an existing file;
     *                   {@code false} to overwrite
     * @param flushEvery flush the writer after this many events; 1 = unbuffered
     */
    public JsonlWriterGateway(String name,
                              Path filePath,
                              long startTime,
                              long endTime,
                              boolean append,
                              int flushEvery) {
        super(name, startTime, endTime);
        this.filePath   = Objects.requireNonNull(filePath, "filePath");
        this.append     = append;
        this.flushEvery = flushEvery;
        setDriveClock(false); // writer is a pure consumer — never drives the clock
    }

    // ------------------------------------------------------------------
    // Runnable — parks until disconnect
    // ------------------------------------------------------------------

    @Override
    public void run() {
        initialize();
        openFile();
        connect();
        setStatus(GatewayStatus.STARTED);

        try {
            stopLatch.await(); // engine dispatch thread calls onEvent(); we park here
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            closeFile();
            setStatus(GatewayStatus.STOPPED);
        }
    }

    // ------------------------------------------------------------------
    // Sub — write each dispatched event as a JSONL line
    // ------------------------------------------------------------------

    @Override
    public synchronized <P extends Datum> void onEvent(Topic<P> topic, P payload) {
        if (writer == null) return; // not yet open or already closed
        try {
            writer.write(codec.toJson(payload));
            writer.newLine();
            if (++writeCount % flushEvery == 0) {
                writer.flush();
            }
        } catch (IOException e) {
            throw new JsonlGatewayException(
                    "Failed to write event to " + filePath, e);
        }
    }

    // ------------------------------------------------------------------
    // Pub — writer never publishes
    // ------------------------------------------------------------------

    @Override
    public <P extends Datum> void publish(Topic<P> topic, P payload) {
        throw new UnsupportedOperationException(
                name() + ": JsonlWriterGateway does not publish events");
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Override
    public synchronized void disconnect() {
        super.disconnect();
        stopLatch.countDown(); // releases the parked run() thread
    }

    // ------------------------------------------------------------------
    // Internal
    // ------------------------------------------------------------------

    private void openFile() {
        try {
            OpenOption[] options = append
                    ? new OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.APPEND}
                    : new OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING};
            writer = Files.newBufferedWriter(filePath, options);
        } catch (IOException e) {
            throw new JsonlGatewayException(
                    "Failed to open JSONL output file: " + filePath, e);
        }
    }

    private synchronized void closeFile() {
        if (writer == null) return;
        try {
            writer.flush();
            writer.close();
        } catch (IOException e) {
            throw new JsonlGatewayException(
                    "Failed to close JSONL output file: " + filePath, e);
        } finally {
            writer = null;
        }
    }
}
