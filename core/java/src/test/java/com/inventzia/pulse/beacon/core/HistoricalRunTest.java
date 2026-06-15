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
import com.inventzia.pulse.beacon.core.gateway.file.JsonlReaderGateway;
import com.inventzia.pulse.beacon.core.gateway.periodic.HeartBeatGateway;
import com.inventzia.pulse.data.datum.Datum;
import com.inventzia.pulse.data.schemas.platform.HeartBeat;
import com.inventzia.pulse.data.schemas.platform.TextMessage;
import org.junit.jupiter.api.RepeatedTest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end run of a compressed-time (historic) replay, laid out as a single
 * scenario: two file streams and a heartbeat — all clock-driving — feed the
 * engine, which merges them into one event-time-ordered stream and delivers it
 * to a client.
 *
 * <p>The body reads top-to-bottom like one of the runnable {@code examples}
 * mains (topics → gateways → engine → clients → wiring → run), but ends in an
 * assertion so it doubles as the automated regression check for the merge.
 * It is a {@link RepeatedTest} because re-running the same wiring 20× guards
 * against non-determinism in the concurrent merge (it once caught a race where
 * a gateway's final event could be dropped).
 */
class HistoricalRunTest {

    private static final List<String> KEY_A    = List.of("A");
    private static final List<String> KEY_BEAT = List.of("BEAT");

    @RepeatedTest(20)
    void historicalReplayMergesEverySourceInEventTimeOrder() throws Exception {

        // 1) Run window. The fixtures are stamped in 2010, so "now" is always
        //    past endTime: the engine runs in deterministic, compressed time.
        final long start = 1_283_630_000_000L;
        final long end   = 1_283_630_007_000L;

        // 2) Topics — one per stream, plus the heartbeat.
        Topic<TextMessage> streamOne = new Topic<>("stream.one", TextMessage.class);
        Topic<TextMessage> streamTwo = new Topic<>("stream.two", TextMessage.class);
        Topic<HeartBeat>   heartbeat = new Topic<>("heartbeat",  HeartBeat.class);

        // 3) Gateways — two JSONL readers and a heartbeat, all clock-driving.
        JsonlReaderGateway<TextMessage> readerOne = new JsonlReaderGateway<>(
                "reader-1", streamOne, KEY_A,
                RunUtils.resource("/examples/data/messages_one.jsonl"), start, end);
        JsonlReaderGateway<TextMessage> readerTwo = new JsonlReaderGateway<>(
                "reader-2", streamTwo, KEY_A,
                RunUtils.resource("/examples/data/messages_two.jsonl"), start, end);
        HeartBeatGateway beater = new HeartBeatGateway(
                "beater", heartbeat, "BEAT", 2_000L, start + 1_500L, start, end);

        // 4) Engine.
        MultiClientEngine engine = new MultiClientEngine("engine", start, end);

        // 5) Clients — a capturing consumer of all three sources. Further
        //    clients would register the same way.
        CapturingConsumer client = new CapturingConsumer("capture");
                
        // 6) Wiring — register the producing gateways and the subscribing client.
        engine.registerPublisher(readerOne, streamOne, KEY_A);
        engine.registerPublisher(readerTwo, streamTwo, KEY_A);
        engine.registerPublisher(beater,    heartbeat, KEY_BEAT);
        engine.registerActor(client,
                Map.of(streamOne, KEY_A, streamTwo, KEY_A, heartbeat, KEY_BEAT),
                Map.of());

        // 7) Run: start the engine, release the producers, wait for completion.
        runToCompletion(engine, readerOne, readerTwo, beater);

        // 8) Verify the merged, strictly time-ordered stream:
        //    7 data events + 3 beats, none dropped, none reordered.
        assertThat(engine.status()).isIn(GatewayStatus.STOPPED, GatewayStatus.COMPLETE);
        assertThat(client.times).containsExactly(
                1_283_630_000_000L,  // stream-one #1
                1_283_630_001_000L,  // stream-two #1
                1_283_630_001_500L,  // beat
                1_283_630_002_000L,  // stream-one #2
                1_283_630_003_000L,  // stream-two #2
                1_283_630_003_500L,  // beat
                1_283_630_004_000L,  // stream-one #3
                1_283_630_005_000L,  // stream-two #3
                1_283_630_005_500L,  // beat
                1_283_630_006_000L); // stream-one #4
    }

    // ---------------------------------------------------------------------
    // A client that records the event time of everything it receives.
    // ---------------------------------------------------------------------
    private static final class CapturingConsumer extends AbstractActor {
        final List<Long> times = Collections.synchronizedList(new ArrayList<>());

        CapturingConsumer(String name) {
            super(name);
        }

        @Override
        public <P extends Datum> void onEvent(Topic<P> topic, P payload) {
            times.add(payload.getDatumTime());
        }
    }

    // ---------------------------------------------------------------------
    // Harness: start the engine, then the producers, then wait for the end.
    // ---------------------------------------------------------------------
    private static void runToCompletion(MultiClientEngine engine, Gateway... producers)
            throws InterruptedException {
        // Start the engine first; it connects (registering the clock-driving
        // gateways with the time machine) and parks at STARTED until they feed it.
        Thread engineThread = new Thread(engine, "engine");
        engineThread.start();
        RunUtils.awaitStatus(engine, GatewayStatus.STARTED, 2_000);

        // Then release the producers.
        for (Gateway producer : producers) {
            new Thread(producer, producer.name()).start();
        }

        engineThread.join(5_000);
    }
}
