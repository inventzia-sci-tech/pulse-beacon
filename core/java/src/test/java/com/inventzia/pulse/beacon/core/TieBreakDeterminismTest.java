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
import com.inventzia.pulse.data.datum.Datum;
import com.inventzia.pulse.data.schemas.platform.HeartBeat;
import org.junit.jupiter.api.RepeatedTest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic tie-breaking for equal-event-time messages. Two sources emit beats at
 * the <em>same</em> times ({@code 1000, 2000}); the dispatch order among those ties must
 * be stable across runs — decided by origin rank (high-priority first), then stable
 * registration index — not by nondeterministic thread arrival. {@link RepeatedTest}
 * guards the stability (the old wall-clock tie-break would vary).
 */
class TieBreakDeterminismTest {

    private static final long START = 1_000_000L;
    private static final long END   = 1_010_000L;
    private static final long[] TIMES = {START + 1_000, START + 2_000};

    @RepeatedTest(10)
    void equalTimeEventsOrderByRegistrationIndex() throws Exception {
        Result r = run(false);
        // A registered before B, both normal priority ⇒ A's event before B's at each tick.
        assertThat(r.delivered()).containsExactly(
                "A@1001000", "B@1001000", "A@1002000", "B@1002000");
    }

    @RepeatedTest(10)
    void highPriorityGatewaySortsFirst() throws Exception {
        Result r = run(true);
        // B is high priority ⇒ B's event before A's at each tick, regardless of index.
        assertThat(r.delivered()).containsExactly(
                "B@1001000", "A@1001000", "B@1002000", "A@1002000");
    }

    private Result run(boolean bHighPriority) throws InterruptedException {
        Topic<HeartBeat> topicA = new Topic<>("hbA", HeartBeat.class);
        Topic<HeartBeat> topicB = new Topic<>("hbB", HeartBeat.class);
        MultiClientEngine engine = new MultiClientEngine("engine", START, END);

        SequenceGateway a = new SequenceGateway("A", topicA, "A", TIMES, START, END);
        SequenceGateway b = new SequenceGateway("B", topicB, "B", TIMES, START, END);
        Capturer capturer = new Capturer("cap");

        engine.registerPublisher(a, topicA, List.of("A"));                 // index 0, normal
        engine.registerPublisher(b, topicB, List.of("B"), bHighPriority);  // index 1, maybe high
        engine.registerActor(capturer,
                Map.of(topicA, List.of("A"), topicB, List.of("B")), Map.of());

        Thread engineThread = new Thread(engine, "engine");
        engineThread.start();
        RunUtils.awaitStatus(engine, GatewayStatus.STARTED, 2_000);
        new Thread(a, "A").start();
        new Thread(b, "B").start();
        engineThread.join(5_000);

        assertThat(engine.status()).isIn(GatewayStatus.STOPPED, GatewayStatus.COMPLETE);
        return new Result(capturer.delivered);
    }

    private record Result(List<String> delivered) { }

    /** A clock-driving source emitting HeartBeats at an explicit sequence of times. */
    private static final class SequenceGateway extends AbstractGateway {
        private final Topic<HeartBeat> topic;
        private final String key;
        private final long[] times;

        SequenceGateway(String name, Topic<HeartBeat> topic, String key, long[] times, long start, long end) {
            super(name, start, end);
            setDriveClock(true);
            this.topic = topic;
            this.key = key;
            this.times = times;
        }

        @Override
        public void run() {
            initialize();
            connect();
            setStatus(GatewayStatus.STARTED);
            for (long t : times) {
                if (!connected()) break;
                Gateway downstream = subscriberForKey(topic, key);
                if (downstream != null) {
                    downstream.onEvent(topic, new HeartBeat(key, t)); // blocks on the write permit
                }
            }
            disconnect();
            setStatus(GatewayStatus.STOPPED);
        }

        @Override public <P extends Datum> void publish(Topic<P> topic, P payload) { }

        @Override public <P extends Datum> void onEvent(Topic<P> topic, P payload) { }
    }

    /** Records "key@time" for everything delivered, in dispatch order. */
    private static final class Capturer extends AbstractActor {
        final List<String> delivered = Collections.synchronizedList(new ArrayList<>());

        Capturer(String name) { super(name); }

        @Override
        public <P extends Datum> void onEvent(Topic<P> topic, P payload) {
            delivered.add(payload.getDatumKey() + "@" + payload.getDatumTime());
        }
    }
}
