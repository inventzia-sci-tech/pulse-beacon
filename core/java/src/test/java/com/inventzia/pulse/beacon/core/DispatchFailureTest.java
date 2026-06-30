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
package com.inventzia.pulse.beacon.core;

import com.inventzia.pulse.beacon.core.examples.RunUtils;
import com.inventzia.pulse.beacon.core.gateway.periodic.HeartBeatGateway;
import com.inventzia.pulse.data.datum.Datum;
import com.inventzia.pulse.data.schemas.platform.HeartBeat;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Failure-path regression tests for the dispatch loop.
 *
 * <p>Guards the two halves of the agreed policy when something throws while an
 * event is being delivered (compressed-time mode):
 *
 * <ul>
 *   <li><b>Actors are isolated.</b> A throwing actor is logged and skipped; the
 *       run still completes and the other actors subscribed to the same event
 *       still receive every event. This also proves no time-machine write permit
 *       is leaked — a leaked permit would block the heartbeat's next event and
 *       the run would hang instead of completing.</li>
 *   <li><b>A subscriber-gateway failure is fatal but clean.</b> The run aborts,
 *       but the engine still tears down: {@code done(te)} runs in a finally and
 *       {@code timeMachine.shutdown()} releases every clock-driver, so the
 *       producer threads unblock and nothing hangs.</li>
 * </ul>
 */
class DispatchFailureTest {

    private static final List<String> KEY_BEAT = List.of("BEAT");

    // Three beats inside the window: start+1500, +3500, +5500.
    private static final long START = 1_283_630_000_000L;
    private static final long END   = 1_283_630_007_000L;

    @RepeatedTest(10)
    void throwingActorIsIsolatedAndOthersStillReceiveEveryEvent() throws Exception {
        Topic<HeartBeat> heartbeat = new Topic<>("heartbeat", HeartBeat.class);
        HeartBeatGateway beater = new HeartBeatGateway(
                "beater", heartbeat, "BEAT", 2_000L, START + 1_500L, START, END);

        MultiClientEngine engine = new MultiClientEngine("engine", START, END);

        ThrowingActor bad = new ThrowingActor("bad");
        CapturingConsumer good = new CapturingConsumer("good");

        engine.registerPublisher(beater, heartbeat, KEY_BEAT);
        engine.registerActor(bad,  Map.of(heartbeat, KEY_BEAT), Map.of());
        engine.registerActor(good, Map.of(heartbeat, KEY_BEAT), Map.of());

        Thread engineThread = new Thread(engine, "engine");
        engineThread.start();
        RunUtils.awaitStatus(engine, GatewayStatus.STARTED, 2_000);
        new Thread(beater, beater.name()).start();
        engineThread.join(5_000);

        // The run completed (no hang from a leaked permit) and the healthy actor
        // saw all three beats despite the other actor throwing on each one.
        assertThat(engineThread.isAlive()).isFalse();
        assertThat(engine.status()).isIn(GatewayStatus.STOPPED, GatewayStatus.COMPLETE);
        assertThat(good.times).containsExactly(
                START + 1_500L, START + 3_500L, START + 5_500L);
        assertThat(bad.calls.get()).isEqualTo(3); // it was invoked for every beat
    }

    @Test
    void throwingSubscriberGatewayAbortsCleanlyWithoutHanging() throws Exception {
        Topic<HeartBeat> heartbeat = new Topic<>("heartbeat", HeartBeat.class);
        HeartBeatGateway beater = new HeartBeatGateway(
                "beater", heartbeat, "BEAT", 2_000L, START + 1_500L, START, END);

        MultiClientEngine engine = new MultiClientEngine("engine", START, END);
        ThrowingSink sink = new ThrowingSink("bad-sink", START, END);

        engine.registerPublisher(beater, heartbeat, KEY_BEAT);
        engine.registerSubscriber(sink, heartbeat, KEY_BEAT);

        Thread engineThread = new Thread(engine, "engine");
        // The engine rethrows on a fatal dispatch failure; swallow it on the
        // engine thread so it does not surface as a noisy uncaught error.
        engineThread.setUncaughtExceptionHandler((t, ex) -> { /* expected */ });
        engineThread.start();
        RunUtils.awaitStatus(engine, GatewayStatus.STARTED, 2_000);

        Thread beaterThread = new Thread(beater, beater.name());
        // Capture anything thrown on the producer thread — notably the
        // "Maximum permit count exceeded" Error that an over-releasing
        // TimeMachine.shutdown() used to raise here (Surefire would log it to
        // stderr but the run still passed, so we assert on it explicitly).
        AtomicReference<Throwable> producerFailure = new AtomicReference<>();
        beaterThread.setUncaughtExceptionHandler((t, ex) -> producerFailure.set(ex));
        beaterThread.start();

        // The engine must terminate (not hang) and the producer must unblock.
        engineThread.join(5_000);
        beaterThread.join(5_000);

        assertThat(engineThread.isAlive()).as("engine terminated").isFalse();
        assertThat(beaterThread.isAlive()).as("producer unblocked, did not hang").isFalse();
        assertThat(producerFailure.get()).as("producer thread threw nothing during abort cleanup").isNull();
        assertThat(engine.status()).isEqualTo(GatewayStatus.STOPPED);
    }

    // ---------------------------------------------------------------------
    // Test doubles
    // ---------------------------------------------------------------------

    /** An actor that throws on every event (and counts how often it was called). */
    private static final class ThrowingActor extends AbstractActor {
        final AtomicInteger calls = new AtomicInteger();

        ThrowingActor(String name) { super(name); }

        @Override
        public <P extends Datum> void onEvent(Topic<P> topic, P payload) {
            calls.incrementAndGet();
            throw new RuntimeException("intentional actor failure");
        }
    }

    /** Records the event time of everything it receives. */
    private static final class CapturingConsumer extends AbstractActor {
        final List<Long> times = Collections.synchronizedList(new ArrayList<>());

        CapturingConsumer(String name) { super(name); }

        @Override
        public <P extends Datum> void onEvent(Topic<P> topic, P payload) {
            times.add(payload.getDatumTime());
        }
    }

    /** A subscriber gateway whose onEvent always throws (a broken boundary). */
    private static final class ThrowingSink extends AbstractGateway {
        ThrowingSink(String name, long start, long end) { super(name, start, end); }

        @Override public void run() { /* passive subscriber: no produce loop */ }

        @Override public <P extends Datum> void publish(Topic<P> topic, P payload) { }

        @Override public <P extends Datum> void onEvent(Topic<P> topic, P payload) {
            throw new RuntimeException("intentional sink failure");
        }
    }
}
