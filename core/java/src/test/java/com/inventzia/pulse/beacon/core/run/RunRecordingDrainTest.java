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
package com.inventzia.pulse.beacon.core.run;

import com.inventzia.pulse.beacon.core.GatewayStatus;
import com.inventzia.pulse.beacon.core.MultiClientEngine;
import com.inventzia.pulse.beacon.core.Topic;
import com.inventzia.pulse.beacon.core.examples.RunUtils;
import com.inventzia.pulse.beacon.core.gateway.periodic.HeartBeatGateway;
import com.inventzia.pulse.beacon.core.gateway.recording.EventRecorderGateway;
import com.inventzia.pulse.data.schemas.platform.HeartBeat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What happens when the recorder will not stop.
 *
 * <p>{@link RunRecording} owns the recorder's writer thread, and at run end it asks the recorder to
 * stop and joins that thread within {@code DRAIN_TIMEOUT_MILLIS}. A recorder wedged on a slow or
 * dead disk must therefore cost the run a bounded delay and nothing more: the join gives up,
 * interrupts, and finalization proceeds. The alternative — waiting forever on a thread that will
 * never return — would hang the JVM at shutdown, with a run directory left saying {@code running}
 * for ever.
 *
 * <p>Two outcomes are distinguished on purpose. A recorder that never terminates cannot report a
 * status, so the manifest says {@code "unknown"} rather than guessing; one that terminates when
 * interrupted reports {@code "interrupted"}. "I do not know what the recording contains" and "the
 * recording was cut short" are different facts, and a reader is entitled to both.
 *
 * <p>These need a recorder that misbehaves on demand, which no real one will do, so they use
 * {@link RunRecording#startWith} to supply one.
 */
class RunRecordingDrainTest {

    private static final long START = 1_283_630_000_000L;   // past window -> COMPRESSED_TIME
    private static final long END   = 1_283_630_005_000L;

    /** The bound RunRecording waits (5s), plus its post-interrupt join (1s), plus slack. */
    private static final long BOUND_MILLIS = 5_000L + 1_000L + 6_000L;

    @Test
    void aRecorderThatIgnoresItsStopRequestCostsABoundedDelayAndLeavesAnUnknownRecording(
            @TempDir Path out) throws Exception {
        WedgedRecorder recorder = new WedgedRecorder("wedged", "rid", START, END, false);
        Harness h = new Harness(out, recorder, "WedgedApp");

        long elapsed = h.runAndTime();

        assertThat(recorder.started.await(5, TimeUnit.SECONDS)).as("the recorder thread ran").isTrue();
        assertThat(elapsed).as("finalization is bounded, not indefinite").isLessThan(BOUND_MILLIS);
        assertThat(recorder.interrupted.get()).as("the join gave up and interrupted the writer").isTrue();

        Map<String, Object> manifest = RunLayout.readManifest(h.paths.dir());
        assertThat(manifest).as("the run is still finalized, not left as 'running'").isNotNull();
        assertThat(manifest.get("endedAt")).isNotNull();
        assertThat(manifest.get("runStatus")).as("the engine itself completed").isEqualTo("completed");
        assertThat(manifest.get("recordingStatus"))
                .as("a recorder that never terminated cannot report a status; say so rather than guess")
                .isEqualTo("unknown");
        assertThat(manifest.get("counts"))
                .as("counts are only meaningful once the writer has terminated").isNull();

        recorder.release();
    }

    @Test
    void aRecorderThatStopsOnInterruptionReportsItAsInterrupted(@TempDir Path out) throws Exception {
        WedgedRecorder recorder = new WedgedRecorder("interruptible", "rid", START, END, true);
        Harness h = new Harness(out, recorder, "InterruptibleApp");

        long elapsed = h.runAndTime();

        assertThat(elapsed).isLessThan(BOUND_MILLIS);
        assertThat(recorder.terminated()).as("it returned once interrupted").isTrue();

        Map<String, Object> manifest = RunLayout.readManifest(h.paths.dir());
        assertThat(manifest.get("runStatus")).isEqualTo("completed");
        assertThat(manifest.get("recordingStatus"))
                .as("terminating on interruption is reportable, and is not the same as 'unknown'")
                .isEqualTo("interrupted");
    }

    // ------------------------------------------------------------------

    /** Engine + heartbeat + a supplied recorder, run to completion; measures how long the end takes. */
    private static final class Harness {
        private final MultiClientEngine engine = new MultiClientEngine("engine", START, END);
        private final HeartBeatGateway beater;
        private final RunRecording run;
        private RunLayout.RunPaths paths;

        Harness(Path out, EventRecorderGateway recorder, String app) {
            Topic<HeartBeat> beats = new Topic<>("hb", HeartBeat.class);
            beater = new HeartBeatGateway("beater", beats, "BEAT", 2_000L, START + 1_000L, START, END);
            engine.registerPublisher(beater, beats, List.of("BEAT"));
            run = RunRecording.startWith(app, engine, "engine:test", out, recorder, "rid");
            run.recordRoute(engine, beats, List.of("BEAT"));
        }

        long runAndTime() throws Exception {
            Thread engineThread = run.scoped(engine, "engine");
            engineThread.start();
            RunUtils.awaitStatus(engine, GatewayStatus.STARTED, 5_000);
            Thread beaterThread = run.scoped(beater, "beater");
            beaterThread.start();

            long began = System.nanoTime();
            engineThread.join(60_000);
            long elapsed = (System.nanoTime() - began) / 1_000_000L;

            assertThat(engineThread.isAlive()).as("the run ended; it did not hang on the recorder")
                    .isFalse();
            beaterThread.join(5_000);
            paths = run.paths();
            assertThat(paths).isNotNull();
            run.close();
            return elapsed;
        }
    }

    /**
     * A recorder whose writer thread ignores {@code requestStop()} and blocks until released —
     * standing in for one wedged on a disk that never completes a write.
     *
     * @param stopOnInterrupt whether it returns when interrupted, or keeps blocking regardless
     */
    private static final class WedgedRecorder extends EventRecorderGateway {

        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicBoolean interrupted = new AtomicBoolean(false);
        private final AtomicBoolean done = new AtomicBoolean(false);
        private final boolean stopOnInterrupt;

        WedgedRecorder(String name, String runId, long start, long end, boolean stopOnInterrupt) {
            super(name, runId, start, end, 1024);
            this.stopOnInterrupt = stopOnInterrupt;
        }

        @Override
        public void run() {
            started.countDown();
            while (true) {
                try {
                    // Never completes on its own: only an interrupt or an explicit release ends this.
                    if (release.await(60, TimeUnit.SECONDS)) {
                        break;
                    }
                } catch (InterruptedException e) {
                    interrupted.set(true);
                    if (stopOnInterrupt) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    // Deliberately swallow it and keep blocking: the harder case, where even an
                    // interrupt does not free the writer.
                }
            }
            done.set(true);
        }

        /** Mirrors the real recorder: "interrupted" once it was, otherwise complete. */
        @Override
        public String recordingStatus() {
            return interrupted.get() ? "interrupted" : "complete";
        }

        @Override
        public boolean terminated() {
            return done.get();
        }

        /** Let the thread finish, so the test does not leave one blocked behind it. */
        void release() {
            release.countDown();
        }
    }
}
