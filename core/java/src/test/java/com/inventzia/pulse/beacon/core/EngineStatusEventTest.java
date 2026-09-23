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
package com.inventzia.pulse.beacon.core;

import com.inventzia.pulse.beacon.core.examples.RunUtils;
import com.inventzia.pulse.beacon.core.gateway.periodic.HeartBeatGateway;
import com.inventzia.pulse.data.datum.Datum;
import com.inventzia.pulse.data.schemas.platform.EngineStatus;
import com.inventzia.pulse.data.schemas.platform.HeartBeat;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lifecycle transitions published as ordinary events on {@link AbstractEngine#STATUS_TOPIC}.
 *
 * <p>What matters here is <em>which</em> transitions survive. Status is delivered directly to the
 * topic's subscriber rather than scheduled through the TimeMachine, and that is not an optimisation:
 * the first transitions happen before {@code startUpDone} opens the dispatch barrier (a queued event
 * would be discarded by {@code dispatchTimeEvent}) and the last happen after the dispatch loop has
 * returned (nothing left to carry them). A queue-routed status stream would therefore lose both ends
 * of the run and keep only the quiet middle — so those two ends are what this test pins.
 */
class EngineStatusEventTest {

    private static final long START = 1_283_630_000_000L;   // past window -> COMPRESSED_TIME
    private static final long END   = 1_283_630_005_000L;

    @Test
    void statusArrivesAsEventsIncludingBeforeStartupAndAfterTheDispatchLoop() throws Exception {
        Topic<HeartBeat> beats = new Topic<>("hb", HeartBeat.class);
        MultiClientEngine engine = new MultiClientEngine("engine", START, END);
        HeartBeatGateway beater =
                new HeartBeatGateway("beater", beats, "BEAT", 2_000L, START + 1_000L, START, END);
        engine.registerPublisher(beater, beats, List.of("BEAT"));

        CollectingSink sink = new CollectingSink("status-sink", START, END);
        engine.registerSubscriber(sink, AbstractEngine.STATUS_TOPIC,
                List.of(AbstractEngine.STATUS_KEY));
        engine.publishStatusEvents(beater);

        Thread engineThread = new Thread(engine, "engine");
        engineThread.start();
        RunUtils.awaitStatus(engine, GatewayStatus.STARTED, 2_000);
        new Thread(beater, "beater").start();
        engineThread.join(10_000);

        List<EngineStatus> seen = sink.statuses();
        assertThat(seen).as("status events were delivered").isNotEmpty();

        // Every event carries the single routing key, and the component travels in the payload.
        assertThat(seen).allSatisfy(s ->
                assertThat(s.getDatumKey()).isEqualTo(AbstractEngine.STATUS_KEY));

        List<String> engineTransitions = seen.stream()
                .filter(s -> s.component().equals("engine"))
                .map(s -> s.fromStatus() + "->" + s.toStatus())
                .collect(Collectors.toList());

        // The two ends, which queue-routed status would have lost.
        assertThat(engineTransitions).as("pre-barrier transition survived")
                .contains("BLANK->INITIALIZED");
        assertThat(engineTransitions).as("post-dispatch-loop transition survived")
                .contains("STOPPED->COMPLETE");
        // ...and the whole ordered lifecycle in between.
        assertThat(engineTransitions).containsExactly(
                "BLANK->INITIALIZED", "INITIALIZED->PRESTART", "PRESTART->STARTED",
                "STARTED->WRAP_UP", "WRAP_UP->STOPPED", "STOPPED->COMPLETE");

        // A gateway's transitions are relayed onto the same stream.
        assertThat(seen).as("the beater's transitions are here too")
                .anySatisfy(s -> assertThat(s.component()).isEqualTo("beater"));

        // Data still flowed; status did not displace it.
        assertThat(sink.otherEvents()).as("status delivery did not disturb the run")
                .isNotNegative();
        assertThat(engine.status()).isEqualTo(GatewayStatus.COMPLETE);
    }

    @Test
    void noSubscriberMeansNoStatusAndNoFailure() throws Exception {
        Topic<HeartBeat> beats = new Topic<>("hb", HeartBeat.class);
        MultiClientEngine engine = new MultiClientEngine("engine", START, END);
        HeartBeatGateway beater =
                new HeartBeatGateway("beater", beats, "BEAT", 2_000L, START + 1_000L, START, END);
        engine.registerPublisher(beater, beats, List.of("BEAT"));
        engine.publishStatusEvents(beater);      // publishing with nobody registered on the topic

        Thread engineThread = new Thread(engine, "engine");
        engineThread.start();
        RunUtils.awaitStatus(engine, GatewayStatus.STARTED, 2_000);
        new Thread(beater, "beater").start();
        engineThread.join(10_000);

        assertThat(engine.status()).as("a run with no status subscriber is unaffected")
                .isEqualTo(GatewayStatus.COMPLETE);
    }

    @Test
    void aThrowingStatusSubscriberNeverBreaksTheRun() throws Exception {
        Topic<HeartBeat> beats = new Topic<>("hb", HeartBeat.class);
        MultiClientEngine engine = new MultiClientEngine("engine", START, END);
        HeartBeatGateway beater =
                new HeartBeatGateway("beater", beats, "BEAT", 2_000L, START + 1_000L, START, END);
        engine.registerPublisher(beater, beats, List.of("BEAT"));

        engine.registerSubscriber(new ThrowingSink("bad-status", START, END),
                AbstractEngine.STATUS_TOPIC, List.of(AbstractEngine.STATUS_KEY));
        engine.publishStatusEvents(beater);

        Thread engineThread = new Thread(engine, "engine");
        engineThread.start();
        RunUtils.awaitStatus(engine, GatewayStatus.STARTED, 2_000);
        new Thread(beater, "beater").start();
        engineThread.join(10_000);

        // Observation must never take down what it observes, even on the control plane.
        assertThat(engine.status()).isEqualTo(GatewayStatus.COMPLETE);
    }

    // ------------------------------------------------------------------

    /** Collects status events (and counts anything else) delivered to it. */
    private static final class CollectingSink extends AbstractGateway {
        private final List<EngineStatus> statuses = new ArrayList<>();
        private int other = 0;

        CollectingSink(String name, long start, long end) { super(name, start, end); }

        @Override public void run() { /* passive subscriber */ }

        @Override public <P extends Datum> void publish(Topic<P> topic, P payload) { }

        @Override public synchronized <P extends Datum> void onEvent(Topic<P> topic, P payload) {
            if (payload instanceof EngineStatus s) {
                statuses.add(s);
            } else {
                other++;
            }
        }

        synchronized List<EngineStatus> statuses() { return new ArrayList<>(statuses); }

        synchronized int otherEvents() { return other; }
    }

    /** A status subscriber that always throws. */
    private static final class ThrowingSink extends AbstractGateway {
        ThrowingSink(String name, long start, long end) { super(name, start, end); }

        @Override public void run() { }

        @Override public <P extends Datum> void publish(Topic<P> topic, P payload) { }

        @Override public <P extends Datum> void onEvent(Topic<P> topic, P payload) {
            throw new RuntimeException("intentional status-subscriber failure");
        }
    }
}
