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
 * Per-gateway timestamp monotonicity: in compressed time a source gateway may not go
 * backwards. A later submission with an <em>earlier</em> timestamp than the gateway
 * already emitted would retroactively reorder dispatched history and break the
 * deterministic merge, so the engine drops it (and logs). Same-tick and later events
 * pass.
 *
 * <p>A single clock-driving source emits {@code 1000, 2000, 1500, 3000}; the backwards
 * {@code 1500} must be dropped, leaving the consumer with {@code 1000, 2000, 3000}.
 */
class GatewayMonotonicityTest {

    private static final long START = 1_000_000L;
    private static final long END   = 1_010_000L;

    @RepeatedTest(5)
    void dropsABackwardsTimestampFromASource() throws Exception {
        Topic<HeartBeat> hb = new Topic<>("hb", HeartBeat.class);
        MultiClientEngine engine = new MultiClientEngine("engine", START, END);

        SequenceGateway src = new SequenceGateway("src", hb, "K",
                new long[] {START + 1_000, START + 2_000, START + 1_500, START + 3_000}, START, END);
        Capturer capturer = new Capturer("cap");

        engine.registerPublisher(src, hb, List.of("K"));
        engine.registerActor(capturer, Map.of(hb, List.of("K")), Map.of());

        Thread engineThread = new Thread(engine, "engine");
        engineThread.start();
        RunUtils.awaitStatus(engine, GatewayStatus.STARTED, 2_000);
        new Thread(src, "src").start();
        engineThread.join(5_000);

        assertThat(engine.status()).isIn(GatewayStatus.STOPPED, GatewayStatus.COMPLETE);
        // the backwards 1500 is dropped; the rest are delivered in event-time order.
        assertThat(capturer.times).containsExactly(START + 1_000, START + 2_000, START + 3_000);
    }

    /** A clock-driving source that emits HeartBeats at an explicit sequence of times. */
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
                    // In compressed time this blocks on the time-machine write permit.
                    downstream.onEvent(topic, new HeartBeat(key, t));
                }
            }
            disconnect();
            setStatus(GatewayStatus.STOPPED);
        }

        @Override public <P extends Datum> void publish(Topic<P> topic, P payload) { }

        @Override public <P extends Datum> void onEvent(Topic<P> topic, P payload) { }
    }

    /** Records the event time of everything delivered to it. */
    private static final class Capturer extends AbstractActor {
        final List<Long> times = Collections.synchronizedList(new ArrayList<>());

        Capturer(String name) { super(name); }

        @Override
        public <P extends Datum> void onEvent(Topic<P> topic, P payload) {
            times.add(payload.getDatumTime());
        }
    }
}
