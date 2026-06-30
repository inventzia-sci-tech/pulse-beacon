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
package com.inventzia.pulse.beacon.core.crosslanguage;

import com.inventzia.pulse.beacon.core.AbstractGateway;
import com.inventzia.pulse.beacon.core.GatewayStatus;
import com.inventzia.pulse.beacon.core.MultiClientEngine;
import com.inventzia.pulse.beacon.core.Topic;
import com.inventzia.pulse.beacon.core.examples.RunUtils;
import com.inventzia.pulse.beacon.core.gateway.periodic.HeartBeatGateway;
import com.inventzia.pulse.data.datum.Datum;
import com.inventzia.pulse.data.schemas.platform.HeartBeat;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for the {@link CrossLanguageGateway} <b>sink</b> termination
 * protocol. Before the fix, {@code disconnect()} only released the source-side
 * {@code stopLatch} and never put an {@code END} on the sink queue, so a foreign
 * streamer draining the sink ({@code run_consume}) blocked forever.
 *
 * <p>Both the normal-completion path and the abnormal (abort) path must end the
 * streamer. A Java thread stands in for the Python
 * {@code CrossLanguageStreamer.run_consume()} loop (drain {@code takeNext},
 * {@code ackDone}, stop on {@code END}).
 */
class CrossLanguageGatewaySinkTest {

    private static final long START = 1_283_630_000_000L;
    private static final long END   = 1_283_630_007_000L; // window holds 3 beats

    @RepeatedTest(10)
    void sinkLoopReceivesLifecycleAndDataThenTerminates() throws Exception {
        Topic<HeartBeat> heartbeat = new Topic<>("heartbeat", HeartBeat.class);
        MultiClientEngine engine = new MultiClientEngine("engine", START, END);
        HeartBeatGateway beater = new HeartBeatGateway(
                "beater", heartbeat, "BEAT", 2_000L, START + 1_500L, START, END);

        CrossLanguageGateway sink = new CrossLanguageGateway("py-sink", START, END);
        sink.setDriveClock(false); // sink-only: no clock driving

        engine.registerPublisher(beater, heartbeat, List.of("BEAT"));
        engine.registerSubscriber(sink, heartbeat, List.of("BEAT"));

        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        Thread streamer = startStreamer(sink, seen);

        Thread engineThread = new Thread(engine, "engine");
        engineThread.start();
        RunUtils.awaitStatus(engine, GatewayStatus.STARTED, 2_000);
        new Thread(beater, "beater").start();
        new Thread(sink, "py-sink").start();

        engineThread.join(10_000);
        streamer.join(10_000);

        // The loop terminated (the bug was an infinite block here), having seen
        // the full engine-driven lifecycle in order: START before any data, the
        // three beats, then STOP and END after — exactly as for an actor.
        assertThat(streamer.isAlive()).as("sink streamer terminated").isFalse();
        assertThat(seen).containsExactly("START", "DATA", "DATA", "DATA", "STOP", "END");
    }

    @Test
    void sinkLoopIsTerminatedWhenTheEngineAbortsOnAFatalError() throws Exception {
        // Two streams: a "bad" one (beats first) whose sink throws — a fatal
        // boundary failure that aborts the engine — and a "good" one feeding the
        // cross-language sink under test. The abort must still end its streamer.
        Topic<HeartBeat> good = new Topic<>("hb.good", HeartBeat.class);
        Topic<HeartBeat> bad  = new Topic<>("hb.bad", HeartBeat.class);

        MultiClientEngine engine = new MultiClientEngine("engine", START, END);
        HeartBeatGateway badBeater = new HeartBeatGateway(
                "bad-beater", bad, "BAD", 2_000L, START + 500L, START, END);
        HeartBeatGateway goodBeater = new HeartBeatGateway(
                "good-beater", good, "GOOD", 2_000L, START + 1_500L, START, END);

        CrossLanguageGateway sink = new CrossLanguageGateway("py-sink", START, END);
        sink.setDriveClock(false);
        FatalSink fatal = new FatalSink("fatal-sink", START, END);

        engine.registerPublisher(badBeater, bad, List.of("BAD"));
        engine.registerPublisher(goodBeater, good, List.of("GOOD"));
        engine.registerSubscriber(sink, good, List.of("GOOD"));
        engine.registerSubscriber(fatal, bad, List.of("BAD"));

        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        Thread streamer = startStreamer(sink, seen);

        Thread engineThread = new Thread(engine, "engine");
        engineThread.setUncaughtExceptionHandler((t, ex) -> { /* abort rethrows; expected */ });
        engineThread.start();
        RunUtils.awaitStatus(engine, GatewayStatus.STARTED, 2_000);
        new Thread(badBeater, "bad-beater").start();
        new Thread(goodBeater, "good-beater").start();
        new Thread(sink, "py-sink").start();

        engineThread.join(10_000);
        streamer.join(10_000);

        // Despite the abnormal termination, the sink streamer was ended (got END)
        // via onAbort() rather than blocking forever in takeNext().
        assertThat(streamer.isAlive()).as("sink streamer terminated on abort").isFalse();
        assertThat(seen).contains("END");
        assertThat(engine.status()).isEqualTo(GatewayStatus.STOPPED);
    }

    /** Starts a stand-in for the Python run_consume loop; records event kinds, stops on END. */
    private static Thread startStreamer(CrossLanguageGateway sink, List<String> seen) {
        Thread t = new Thread(() -> {
            try {
                while (true) {
                    CrossLanguageEvent ev = sink.takeNext();
                    if (ev.kind() == CrossLanguageEvent.Kind.END) {
                        seen.add("END");
                        break;
                    }
                    seen.add(ev.kind().name());
                    sink.ackDone();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "sink-streamer");
        t.start();
        return t;
    }

    /** A subscriber gateway whose onEvent always throws — a broken boundary (fatal). */
    private static final class FatalSink extends AbstractGateway {
        FatalSink(String name, long start, long end) { super(name, start, end); }

        @Override public void run() { /* passive subscriber */ }

        @Override public <P extends Datum> void publish(Topic<P> topic, P payload) { }

        @Override public <P extends Datum> void onEvent(Topic<P> topic, P payload) {
            throw new RuntimeException("intentional fatal sink failure");
        }
    }
}
