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

import com.inventzia.pulse.beacon.core.AbstractGateway;
import com.inventzia.pulse.beacon.core.GatewayStatus;
import com.inventzia.pulse.beacon.core.MultiClientEngine;
import com.inventzia.pulse.beacon.core.OperatingMode;
import com.inventzia.pulse.beacon.core.RunInfo;
import com.inventzia.pulse.beacon.core.Topic;
import com.inventzia.pulse.beacon.core.examples.RunUtils;
import com.inventzia.pulse.beacon.core.gateway.periodic.HeartBeatGateway;
import com.inventzia.pulse.beacon.core.gateway.recording.EventRecorderGateway;
import com.inventzia.pulse.data.datum.Datum;
import com.inventzia.pulse.data.schemas.platform.HeartBeat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Failure-path guarantees for run recording — the ones the Pulse Events Viewer relies on when it
 * opens a run that did <em>not</em> go well. The happy path is covered by the bar examples; what is
 * pinned here is the contract for damaged runs:
 *
 * <ol>
 *   <li><b>A failed run still leaves a readable run.</b> When the engine dies mid-dispatch the
 *       manifest is still finalized ({@code runStatus: "failed"}, {@code endedAt} set) and the events
 *       already queued are still flushed, so a crash yields a partial recording rather than none —
 *       and finalization never swallows the engine's own exception.</li>
 *   <li><b>Run outcome and recording outcome are independent.</b> A recorder can fail outright while
 *       the run itself is fine; the two statuses travel on separate manifest fields so the viewer can
 *       say "run ok, recording failed" instead of guessing.</li>
 *   <li><b>Every observed event is accounted for.</b> {@code observed == events + overflow +
 *       serializationErrors + abandoned} holds under queue overflow and under shutdown with work
 *       still queued. This identity is the viewer's trust anchor (it is what
 *       {@code event_record.validate} checks on the Python side): it is what lets the viewer say
 *       "3 of 5 events captured" honestly rather than presenting a lossy recording as complete.</li>
 * </ol>
 *
 * <p><b>Not covered here</b> (deliberately deferred, see the project notes): recorder-drain timeout
 * and interruption, concurrent-run console isolation, and repeated finalization. Nothing the viewer
 * renders depends on those. Note also that case 2 is exercised against the recorder and the manifest
 * directly rather than end-to-end, because {@link RunRecording} builds its own
 * {@link EventRecorderGateway} internally with no injection seam — adding one is the prerequisite for
 * an end-to-end recorder-failure test.
 */
class RunRecordingFailureTest {

    private static final long START = 1_283_630_000_000L; // a window entirely in the past
    private static final long END   = 1_283_630_005_000L; // -> COMPRESSED_TIME

    // ------------------------------------------------------------------
    // 1. Engine fails mid-run
    // ------------------------------------------------------------------

    /**
     * A fatal dispatch failure (a subscriber gateway throwing — fatal by the engine's agreed policy,
     * unlike a throwing actor, which is isolated) must still produce a complete, readable run
     * directory describing the failure, with whatever was recorded before the abort preserved.
     */
    @Test
    void engineFailureStillFinalizesAReadableRunWithItsPartialRecording(@TempDir Path out) throws Exception {
        Topic<HeartBeat> recorded = new Topic<>("rec", HeartBeat.class);
        Topic<HeartBeat> poisoned = new Topic<>("bad", HeartBeat.class);

        MultiClientEngine engine = new MultiClientEngine("engine", START, END);

        // One beat on each route; the recorded one is earlier in event time, so it is dispatched (and
        // queued for the recorder) before the poisoned route brings the run down.
        HeartBeatGateway recBeater =
                new HeartBeatGateway("rec-beater", recorded, "REC", 10_000L, START + 1_000L, START, END);
        HeartBeatGateway badBeater =
                new HeartBeatGateway("bad-beater", poisoned, "BAD", 10_000L, START + 3_000L, START, END);
        engine.registerPublisher(recBeater, recorded, List.of("REC"));
        engine.registerPublisher(badBeater, poisoned, List.of("BAD"));

        RunLayout.RunPaths paths;
        AtomicReference<Throwable> engineFailure = new AtomicReference<>();

        try (RunRecording rr = RunRecording.start(
                "FailingApp", engine, START, END, "engine:test", 1_024, out)) {

            rr.recordRoute(engine, recorded, List.of("REC"));
            engine.registerSubscriber(new ThrowingSink("poison", START, END), poisoned, List.of("BAD"));

            Thread engineThread = rr.scoped(engine, "engine");
            // The engine rethrows a fatal dispatch failure on its own thread; capture it rather than
            // letting it print, and assert below that finalization did not mask it.
            engineThread.setUncaughtExceptionHandler((t, e) -> engineFailure.set(e));
            engineThread.start();

            RunUtils.awaitStatus(engine, GatewayStatus.STARTED, 5_000);
            Thread recThread = rr.scoped(recBeater, "rec-beater");
            Thread badThread = rr.scoped(badBeater, "bad-beater");
            recThread.start();
            badThread.start();

            engineThread.join(15_000);
            recThread.join(5_000);
            badThread.join(5_000);

            assertThat(engineThread.isAlive()).as("engine terminated").isFalse();
            paths = rr.paths();
            assertThat(paths).as("run directory was created before dispatch").isNotNull();
        }

        // The engine's own failure still reached the caller: finalization is best-effort and must
        // never swallow it.
        assertThat(engineFailure.get()).as("engine exception propagated, not masked by finalization")
                .isNotNull();
        assertThat(engine.status()).isEqualTo(GatewayStatus.STOPPED);

        // The run is on disk and readable, and says plainly that it failed.
        Map<String, Object> manifest = RunLayout.readManifest(paths.dir());
        assertThat(manifest).as("manifest is present and parseable after a crash").isNotNull();
        assertThat(manifest.get("runStatus")).isEqualTo("failed");
        assertThat(manifest.get("endedAt")).as("finalized, not left 'running'").isNotNull();
        assertThat(manifest.get("recordingStatus")).as("recorder reported separately")
                .isIn("complete", "interrupted", "failed", "unknown");

        // Whatever had been queued before the abort was still flushed: a crash yields a partial
        // recording, not an empty one.
        assertThat(Files.exists(paths.events())).isTrue();
        List<String> lines = Files.readAllLines(paths.events());
        assertThat(count(lines, "\"kind\":\"header\"")).isEqualTo(1);
        assertThat(count(lines, "\"kind\":\"event\"")).as("pre-crash events preserved").isGreaterThanOrEqualTo(1);
        assertThat(count(lines, "\"kind\":\"trailer\"")).as("recording closed off").isEqualTo(1);

        assertBalanced(manifest);
    }

    // ------------------------------------------------------------------
    // 2. The recorder fails while the run is fine
    // ------------------------------------------------------------------

    /**
     * A recorder that cannot even open its output must mark itself failed, terminate rather than hang,
     * and still account for every event it observed — all of them abandoned, none silently lost.
     */
    @Test
    void recorderThatCannotOpenItsFileFailsAndStillAccountsForEveryEvent(@TempDir Path out) throws Exception {
        // A directory where the events file should be: openFile() cannot write here.
        Path events = out.resolve("events.jsonl");
        Files.createDirectory(events);

        EventRecorderGateway recorder = new EventRecorderGateway("rec", "rid-1", START, END, 16);
        recorder.configure(events, info());

        Thread writer = new Thread(recorder, "rec-io");
        writer.start();
        writer.join(10_000);

        assertThat(writer.isAlive()).as("a broken recorder terminates, it does not hang").isFalse();
        assertThat(recorder.terminated()).isTrue();
        assertThat(recorder.recordingStatus()).isEqualTo("failed");

        // Events offered to an already-failed recorder are rejected, and show up as abandoned.
        Topic<HeartBeat> topic = new Topic<>("rec", HeartBeat.class);
        for (int i = 0; i < 5; i++) {
            recorder.onEvent(topic, new HeartBeat("K", START + i));
        }
        EventRecorderGateway.Counts c = recorder.counts();
        assertThat(c.observed()).isEqualTo(5);
        assertThat(c.events()).isZero();
        assertThat(c.abandoned()).isEqualTo(5);
        assertBalanced(c);
    }

    /**
     * The manifest shape the viewer must be able to render: the run succeeded, its recording did not.
     * Engine outcome and recording outcome are separate fields precisely so this state is expressible.
     */
    @Test
    void manifestCanReportACompletedRunWithAFailedRecording(@TempDir Path out) {
        RunLayout.RunPaths paths = RunLayout.createRun(
                out, OperatingMode.COMPRESSED_TIME, "App", "20260101T000000Z-abcdef", "engine:test",
                new long[]{START, END}, "fp", List.of("com.inventzia.pulse.data"),
                Map.of("capture", "selected-routes", "sequence", "recorder-local"));

        // Mid-run the manifest already exists, marked as in flight — a viewer listing runs sees this.
        Map<String, Object> inFlight = RunLayout.readManifest(paths.dir());
        assertThat(inFlight.get("runStatus")).isEqualTo("running");
        assertThat(inFlight.get("endedAt")).isNull();

        RunLayout.finalizeRun(paths.dir(), "completed", "failed",
                new RunLayout.RecordingCounts(5, 0, 0, 0, 5));

        Map<String, Object> m = RunLayout.readManifest(paths.dir());
        assertThat(m.get("runStatus")).as("the run itself was fine").isEqualTo("completed");
        assertThat(m.get("recordingStatus")).as("its recording was not").isEqualTo("failed");
        assertThat(m.get("endedAt")).isNotNull();
        assertBalanced(m);
    }

    // ------------------------------------------------------------------
    // 6. Shutdown with events still queued / overflowing
    // ------------------------------------------------------------------

    /**
     * The accounting identity under pressure: a queue far too small for the burst it is given, stopped
     * while work is still queued. Drops must be counted as overflow, the drain must still complete,
     * and what reached the file must equal the {@code events} count exactly.
     */
    @Test
    void shutdownWithQueuedEventsAccountsForEveryObservedEvent(@TempDir Path out) throws Exception {
        Path events = out.resolve("events.jsonl");
        EventRecorderGateway recorder = new EventRecorderGateway("rec", "rid-2", START, END, 4);
        recorder.configure(events, info());

        Thread writer = new Thread(recorder, "rec-io");
        writer.start();

        Topic<HeartBeat> topic = new Topic<>("rec", HeartBeat.class);
        final int burst = 500;
        for (int i = 0; i < burst; i++) {
            recorder.onEvent(topic, new HeartBeat("K", START + i));
        }

        recorder.requestStop();
        writer.join(10_000);

        assertThat(writer.isAlive()).as("drain completed").isFalse();
        assertThat(recorder.terminated()).isTrue();

        EventRecorderGateway.Counts c = recorder.counts();
        assertThat(c.observed()).as("every offered event was observed").isEqualTo(burst);
        assertBalanced(c);

        // What the file holds must match what the counters claim — no phantom events either way.
        List<String> lines = Files.readAllLines(events);
        assertThat(count(lines, "\"kind\":\"header\"")).isEqualTo(1);
        assertThat(count(lines, "\"kind\":\"event\"")).isEqualTo((int) c.events());
        assertThat(count(lines, "\"kind\":\"trailer\"")).isEqualTo(1);

        // The trailer republishes the same accounting, so a reader never has to trust the manifest alone.
        String trailer = lines.stream().filter(l -> l.contains("\"kind\":\"trailer\"")).findFirst().orElseThrow();
        assertThat(trailer).contains("\"observed\":" + c.observed())
                           .contains("\"events\":" + c.events())
                           .contains("\"overflow\":" + c.overflow())
                           .contains("\"abandoned\":" + c.abandoned());
    }

    /**
     * Events arriving after a stop has been requested are abandoned, not silently dropped: the run is
     * still recorded as {@code complete} (nothing broke) but the counts show the loss.
     */
    @Test
    void eventsArrivingAfterStopAreCountedAsAbandoned(@TempDir Path out) throws Exception {
        Path events = out.resolve("events.jsonl");
        EventRecorderGateway recorder = new EventRecorderGateway("rec", "rid-3", START, END, 64);
        recorder.configure(events, info());

        recorder.requestStop();                       // stop before anything is offered

        Topic<HeartBeat> topic = new Topic<>("rec", HeartBeat.class);
        for (int i = 0; i < 50; i++) {
            recorder.onEvent(topic, new HeartBeat("K", START + i));
        }

        Thread writer = new Thread(recorder, "rec-io");
        writer.start();
        writer.join(10_000);

        assertThat(recorder.terminated()).isTrue();
        assertThat(recorder.recordingStatus()).as("nothing failed; the recording is just empty")
                .isEqualTo("complete");

        EventRecorderGateway.Counts c = recorder.counts();
        assertThat(c.observed()).isEqualTo(50);
        assertThat(c.events()).isZero();
        assertThat(c.abandoned()).isEqualTo(50);
        assertBalanced(c);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static RunInfo info() {
        return new RunInfo(OperatingMode.COMPRESSED_TIME, START, END, "fp",
                List.of("com.inventzia.pulse.data"));
    }

    private static int count(List<String> lines, String needle) {
        return (int) lines.stream().filter(l -> l.contains(needle)).count();
    }

    /** {@code observed == events + overflow + serializationErrors + abandoned}. */
    private static void assertBalanced(EventRecorderGateway.Counts c) {
        assertThat(c.events() + c.overflow() + c.serializationErrors() + c.abandoned())
                .as("accounting identity: observed == events + overflow + serializationErrors + abandoned")
                .isEqualTo(c.observed());
    }

    /** The same identity, as the viewer reads it out of {@code run.json}. */
    @SuppressWarnings("unchecked")
    private static void assertBalanced(Map<String, Object> manifest) {
        Object raw = manifest.get("counts");
        assertThat(raw).as("finalized manifest carries counts").isInstanceOf(Map.class);
        Map<String, Object> counts = (Map<String, Object>) raw;
        long observed = num(counts, "observed");
        long sum = num(counts, "events") + num(counts, "overflow")
                 + num(counts, "serializationErrors") + num(counts, "abandoned");
        assertThat(sum).as("manifest counts balance").isEqualTo(observed);
    }

    private static long num(Map<String, Object> m, String key) {
        Object v = m.get(key);
        assertThat(v).as("counts." + key).isInstanceOf(Number.class);
        return ((Number) v).longValue();
    }

    /** A subscriber gateway whose onEvent always throws — a fatal boundary failure for the engine. */
    private static final class ThrowingSink extends AbstractGateway {
        ThrowingSink(String name, long start, long end) { super(name, start, end); }

        @Override public void run() { /* passive subscriber */ }

        @Override public <P extends Datum> void publish(Topic<P> topic, P payload) { }

        @Override public <P extends Datum> void onEvent(Topic<P> topic, P payload) {
            throw new RuntimeException("intentional sink failure");
        }
    }
}
