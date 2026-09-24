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
package com.inventzia.pulse.beacon.core.gateway.recording;

import com.inventzia.pulse.beacon.core.OperatingMode;
import com.inventzia.pulse.beacon.core.RunInfo;
import com.inventzia.pulse.beacon.core.Topic;
import com.inventzia.pulse.data.schemas.platform.HeartBeat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * When a recording becomes visible to someone following it.
 *
 * <p>The writer flushes as soon as it finds nothing to do, which covers a quiet stream. A *busy* one
 * never reaches that branch: every {@code poll} returns a record, so without a periodic flush the
 * events sit in the writer's buffer until it fills. At a few hundred bytes an event and an 8 KB
 * buffer that is dozens of events of latency — a live viewer showing nothing while the run is
 * plainly producing.
 *
 * <p>The tests keep the writer continuously fed (so the idle flush never fires) while writing few
 * enough bytes to stay inside the buffer (so a buffer-full flush cannot rescue it either). What
 * reaches the file in that window is therefore down to the periodic flush alone.
 */
class RecorderFlushTest {

    private static final long START = 1_283_630_000_000L;
    private static final long END   = 1_283_630_005_000L;

    private static final Topic<HeartBeat> TOPIC = new Topic<>("hb", HeartBeat.class);

    /** Slower than the writer's 200 ms poll, so it never sees an empty queue; ~25 events/second. */
    private static final long FEED_INTERVAL_MILLIS = 40L;

    private static RunInfo info() {
        return new RunInfo(OperatingMode.REAL_TIME, START, END, "fp",
                List.of("com.inventzia.pulse.data"));
    }

    private static int eventLines(Path file) throws Exception {
        if (!Files.exists(file)) {
            return 0;
        }
        return (int) Files.readAllLines(file).stream()
                .filter(l -> l.contains("\"kind\":\"event\""))
                .count();
    }

    /**
     * Wait until the file holds at least {@code want} events, or give up.
     *
     * <p>Sampling at a fixed instant would be measuring the JVM as much as the recorder: the first
     * serialization loads the codec and Jackson, which on a cold fork costs more than the window
     * being tested. What matters is that the events arrive <em>while the stream is still busy</em>,
     * not that they arrive by a particular millisecond.
     */
    private static int awaitEvents(Path file, int want, long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        int seen = 0;
        while (System.currentTimeMillis() < deadline) {
            seen = eventLines(file);
            if (seen >= want) {
                return seen;
            }
            Thread.sleep(25L);
        }
        return seen;
    }

    /** Feed the recorder steadily on another thread, so its queue is never empty. */
    private static Thread feeder(EventRecorderGateway recorder, int count) {
        Thread t = new Thread(() -> {
            for (int i = 0; i < count; i++) {
                recorder.onEvent(TOPIC, new HeartBeat("BEAT", START + i));
                try {
                    Thread.sleep(FEED_INTERVAL_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "feeder");
        t.setDaemon(true);
        return t;
    }

    @Test
    void aBusyRecordingIsVisibleWhileItIsStillBeingWritten(@TempDir Path dir) throws Exception {
        Path events = dir.resolve("events.jsonl");
        EventRecorderGateway recorder = new EventRecorderGateway("rec", "rid", START, END, 1024);
        recorder.setFlushIntervalMillis(100L);
        recorder.configure(events, info());

        Thread writer = new Thread(recorder, "rec-io");
        writer.start();
        Thread feed = feeder(recorder, 40);        // ~1.6s of steady traffic
        feed.start();

        // While the feeder is still going: the point is that a follower does not have to wait for
        // the run to end, or even to pause. The feeder runs ~1.6s, so this stays inside that window.
        int midRun = awaitEvents(events, 1, 1_200L);
        assertThat(feed.isAlive()).as("still mid-stream when the events became visible").isTrue();

        recorder.requestStop();
        feed.interrupt();
        writer.join(10_000);

        assertThat(midRun)
                .as("events must reach the file while the stream is still busy, not only at the end")
                .isGreaterThan(0);
        assertThat(recorder.terminated()).isTrue();

        // And nothing was lost by flushing early: the accounting still balances.
        EventRecorderGateway.Counts c = recorder.counts();
        assertThat(c.events() + c.overflow() + c.serializationErrors() + c.abandoned())
                .isEqualTo(c.observed());
        assertThat(eventLines(events)).isEqualTo((int) c.events());
    }

    @Test
    void zeroIntervalFlushesEveryRecord(@TempDir Path dir) throws Exception {
        Path events = dir.resolve("events.jsonl");
        EventRecorderGateway recorder = new EventRecorderGateway("rec", "rid", START, END, 1024);
        recorder.setFlushIntervalMillis(0L);       // every record, for a viewer that wants no lag
        recorder.configure(events, info());

        Thread writer = new Thread(recorder, "rec-io");
        writer.start();
        Thread feed = feeder(recorder, 20);
        feed.start();

        int midRun = awaitEvents(events, 3, 1_200L);

        recorder.requestStop();
        feed.interrupt();
        writer.join(10_000);

        assertThat(midRun).as("with no interval, each record is on disk as it is written")
                .isGreaterThanOrEqualTo(3);
    }

    @Test
    void theIntervalCannotBeChangedOnceTheWriterHasStarted(@TempDir Path dir) throws Exception {
        Path events = dir.resolve("events.jsonl");
        EventRecorderGateway recorder = new EventRecorderGateway("rec", "rid", START, END, 16);
        recorder.configure(events, info());

        Thread writer = new Thread(recorder, "rec-io");
        writer.start();
        Thread.sleep(200L);

        try {
            recorder.setFlushIntervalMillis(1_000L);
            org.junit.jupiter.api.Assertions.fail("changing the interval mid-run should be refused");
        } catch (IllegalStateException expected) {
            assertThat(expected).hasMessageContaining("already started");
        } finally {
            recorder.requestStop();
            writer.join(10_000);
        }
    }

    @Test
    void theDefaultIsAShortBoundNotAnUnboundedBuffer() {
        assertThat(EventRecorderGateway.DEFAULT_FLUSH_INTERVAL_MILLIS)
                .as("a viewer following a run should never wait long for it")
                .isGreaterThan(0L)
                .isLessThanOrEqualTo(500L);
    }
}
