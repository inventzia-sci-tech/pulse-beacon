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

import com.inventzia.pulse.beacon.core.AbstractEngine;
import com.inventzia.pulse.beacon.core.Gateway;
import com.inventzia.pulse.beacon.core.RunInfo;
import com.inventzia.pulse.beacon.core.RunListener;
import com.inventzia.pulse.beacon.core.RunOutcome;
import com.inventzia.pulse.beacon.core.Topic;
import com.inventzia.pulse.beacon.core.gateway.recording.EventRecorderGateway;

import org.slf4j.MDC;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Launcher-side orchestration that ties one engine run to the Pulse output layout: a run directory, a
 * {@code run.json} manifest, an {@code events.jsonl} recording, and a per-run {@code console.log} — all
 * driven from the engine's own lifecycle, with the engine itself knowing nothing about any of it.
 *
 * <p><b>Ownership and lifecycle.</b> A launcher creates one of these, registers the routes to record,
 * then runs the engine on a {@linkplain #scoped run-scoped thread}. This object attaches to the engine
 * as a {@link RunListener}:
 * <ul>
 *   <li>at {@link #onRunInitialized}, on the engine thread before dispatch, it creates the run
 *       directory from the engine's <em>authoritative</em> {@link RunInfo} (the operating mode is the
 *       engine's, never re-derived here), attaches the console capture, configures the recorder with
 *       the run's file and metadata, and starts the recorder's writer thread — which this object owns;</li>
 *   <li>at {@link #onRunTerminated}, it stops, drains, and joins the recorder within a bounded timeout,
 *       reads the recording's outcome and counts <em>after</em> the writer terminates, finalizes the
 *       manifest (engine outcome and recording outcome kept separate), and closes the console.</li>
 * </ul>
 *
 * <p><b>Exceptional paths.</b> If {@code engine.run()} throws, {@link #onRunTerminated} still fires
 * (the engine notifies terminals in every exit path), so finalization happens there. {@link #close()}
 * is a belt-and-braces finalizer for the case where the run never terminated cleanly (or was never
 * initialised): it finalizes an {@code unknown} outcome if nothing else did, stops the recorder, and
 * closes the console. Finalization is idempotent (guarded), and {@link #close()} never throws, so it
 * can sit in a try-with-resources around a run without masking the engine's own exception.
 *
 * <p><b>Stage A.</b> The recorder is registered as a subscriber sink on selected routes and stamps a
 * recorder-local sequence; both facts are written into the manifest's {@code recording} descriptor so
 * a reader never mistakes a partial recording for the complete engine stream. The all-routes engine
 * tap with a global dispatch sequence is Stage B.
 */
public final class RunRecording implements RunListener, AutoCloseable {

    /** Bounded time to wait for the recorder's writer thread to drain and close at shutdown. */
    private static final long DRAIN_TIMEOUT_MILLIS = 5_000L;

    private final String app;
    private final String runId;
    private final String source;
    private final Path   root;
    private final EventRecorderGateway recorder;
    private final List<String> routes = new ArrayList<>();

    private final AtomicBoolean finalized = new AtomicBoolean(false);

    private volatile RunLayout.RunPaths paths;   // set at onRunInitialized
    private volatile RunConsole console;          // set at onRunInitialized
    private volatile Thread recorderThread;       // owned; started at onRunInitialized

    private RunRecording(String app, String runId, String source, Path root,
                         EventRecorderGateway recorder) {
        this.app    = app;
        this.runId  = runId;
        this.source = source;
        this.root   = root;
        this.recorder = recorder;
    }

    /**
     * Begin recording a run. Attaches to {@code engine} as a {@link RunListener} (so it must be called
     * before {@code engine.run()}). Register the routes to capture with {@link #recordRoute}, then run
     * the engine on a thread from {@link #scoped}.
     *
     * @param app       application name (the run-directory tier under its mode)
     * @param engine    the engine to observe
     * @param startTime run window start (epoch millis)
     * @param endTime   run window end (epoch millis)
     * @param source    free-form source identity for the manifest (e.g. {@code "engine:Engine"})
     * @param capacity  recorder queue capacity (overflow drops, counted)
     * @param root      output root, or {@code null} to resolve from {@code $PULSE_OUTPUT}/default
     * @return the started recording
     */
    public static RunRecording start(String app, AbstractEngine engine, long startTime, long endTime,
                                     String source, int capacity, Path root) {
        Objects.requireNonNull(app, "app");
        String runId = RunLayout.newRunId();
        return startWith(app, engine, source, root,
                new EventRecorderGateway(app + "-recorder", runId, startTime, endTime, capacity),
                runId);
    }

    /**
     * Begin recording with a caller-supplied recorder, for tests that need one which misbehaves on
     * purpose.
     *
     * <p>The failure paths worth pinning here — a recorder that ignores {@code requestStop()} and has
     * to be interrupted, one that fails at a chosen moment — cannot be provoked through a real
     * recorder's public surface, so the recorder has to be substituted. Package-private: production
     * callers use {@link #start}, which builds its own.
     *
     * @param recorder the recorder to own and drive
     * @param runId    the run id the recorder was constructed with; they must agree, since it names
     *                 the run directory and is stamped on every record
     */
    static RunRecording startWith(String app, AbstractEngine engine, String source, Path root,
                                  EventRecorderGateway recorder, String runId) {
        Objects.requireNonNull(app, "app");
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(recorder, "recorder");
        RunRecording rr = new RunRecording(app, runId, source, root, recorder);
        engine.addRunListener(rr);
        return rr;
    }

    /** The recorder gateway; register it on routes via {@link #recordRoute}. */
    public EventRecorderGateway recorder() {
        return recorder;
    }

    /** The run id (also the run-directory name), known before the directory exists. */
    public String runId() {
        return runId;
    }

    /** The run's paths, available after {@link #onRunInitialized} (null before the run starts). */
    public RunLayout.RunPaths paths() {
        return paths;
    }

    /**
     * Register the recorder as the subscriber sink for {@code (topic, keys)} on {@code engine}, and note
     * the route in the manifest's recording descriptor. Beacon routing is one-to-one, so the route must
     * have no other sink (Stage A limitation).
     */
    public void recordRoute(AbstractEngine engine, Topic<?> topic, List<String> keys) {
        engine.registerSubscriber(recorder, topic, keys);
        routes.add(topic.name());
    }

    /**
     * Record the run's lifecycle alongside its data: register the recorder on the engine's status
     * topic and ask the engine to publish its own and {@code gateways}' transitions there.
     *
     * <p>Status arrives as ordinary events, so it needs no special handling anywhere downstream —
     * it sorts, filters and follows exactly like the data around it.
     *
     * @param engine   the engine to observe
     * @param gateways gateways whose transitions to record alongside the engine's
     */
    public void recordStatus(AbstractEngine engine, Gateway... gateways) {
        recordRoute(engine, AbstractEngine.STATUS_TOPIC, List.of(AbstractEngine.STATUS_KEY));
        engine.publishStatusEvents(gateways);
    }

    /**
     * Wrap a runnable so that, on whatever thread runs it, the run id is present in the SLF4J MDC for
     * the duration — so that thread's log records are captured into this run's {@code console.log}. Use
     * it to start the engine thread and any source-gateway threads. Returns a fresh {@link Thread}.
     */
    public Thread scoped(Runnable body, String threadName) {
        return new Thread(() -> {
            MDC.put(RunLayout.MDC_RUN_ID, runId);
            try {
                body.run();
            } finally {
                MDC.remove(RunLayout.MDC_RUN_ID);
            }
        }, threadName);
    }

    // ------------------------------------------------------------------
    // RunListener — driven by the engine, on the engine thread
    // ------------------------------------------------------------------

    @Override
    public void onRunInitialized(RunInfo info) {
        // Create the run directory from the engine's authoritative mode + type universe.
        Map<String, Object> recording = new LinkedHashMap<>();
        recording.put("capture", "selected-routes");
        recording.put("sequence", "recorder-local");
        recording.put("envelopeVersion", EventRecorderGateway.ENVELOPE_VERSION);
        recording.put("routes", new ArrayList<>(routes));

        this.paths = RunLayout.createRun(root, info.mode(), app, runId, source,
                new long[]{info.startTime(), info.endTime()},
                info.typeFingerprint(), info.providerIds(), recording);

        // Per-run console capture, then the recorder's writer thread (owned here).
        this.console = RunConsole.attach(runId, paths.console());
        recorder.configure(paths.events(), info);
        this.recorderThread = scoped(recorder, app + "-recorder-io");
        this.recorderThread.start();
    }

    @Override
    public void onRunTerminated(RunOutcome outcome) {
        finalizeRun(outcome.runStatus());
    }

    // ------------------------------------------------------------------
    // AutoCloseable — belt-and-braces finalizer; idempotent; never throws
    // ------------------------------------------------------------------

    @Override
    public void close() {
        // If the engine terminated normally this already ran; otherwise (an exception before terminal
        // notification, or a run that never initialised) finalize an unknown outcome now.
        finalizeRun("unknown");
    }

    /**
     * Stop and drain the recorder, read its outcome, and write the final manifest — exactly once. The
     * first caller (normally {@link #onRunTerminated}) wins; later calls (e.g. {@link #close()}) are
     * no-ops. Never throws.
     *
     * @param runStatus the engine outcome to record
     */
    private void finalizeRun(String runStatus) {
        if (!finalized.compareAndSet(false, true)) {
            return; // already finalized
        }
        try {
            if (paths == null) {
                // The run never initialised (e.g. initialize() failed): no directory to finalize.
                return;
            }
            EventRecorderGateway.Counts counts = null;
            String recordingStatus = "unknown";
            if (recorderThread != null) {
                recorder.requestStop();
                joinBounded(recorderThread, DRAIN_TIMEOUT_MILLIS);
                if (recorder.terminated()) {
                    recordingStatus = recorder.recordingStatus();
                    EventRecorderGateway.Counts c = recorder.counts();
                    counts = c;
                }
            }
            RunLayout.RecordingCounts rc = counts == null ? null : new RunLayout.RecordingCounts(
                    counts.observed(), counts.events(), counts.overflow(),
                    counts.serializationErrors(), counts.abandoned());
            RunLayout.finalizeRun(paths.dir(), runStatus, recordingStatus, rc);
        } catch (Throwable t) {
            // Finalization is best-effort and must never mask the engine's own exception.
            System.err.println("RunRecording: failed to finalize run " + runId + ": " + t);
        } finally {
            RunConsole c = console;
            if (c != null) {
                c.close();
            }
        }
    }

    /** Join {@code t} within {@code timeoutMillis}; if it is still alive, interrupt it and join briefly. */
    private static void joinBounded(Thread t, long timeoutMillis) {
        try {
            t.join(timeoutMillis);
            if (t.isAlive()) {
                t.interrupt();
                t.join(1_000L);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
