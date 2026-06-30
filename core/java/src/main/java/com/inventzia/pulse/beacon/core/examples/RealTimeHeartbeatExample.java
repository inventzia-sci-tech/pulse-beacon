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
package com.inventzia.pulse.beacon.core.examples;

import com.inventzia.pulse.beacon.core.ComponentReporter;
import com.inventzia.pulse.beacon.core.GatewayStatus;
import com.inventzia.pulse.beacon.core.MultiClientEngine;
import com.inventzia.pulse.beacon.core.Slf4jReporter;
import com.inventzia.pulse.beacon.core.Topic;
import com.inventzia.pulse.beacon.core.gateway.periodic.HeartBeatGateway;
import com.inventzia.pulse.data.schemas.platform.HeartBeat;

import java.util.List;
import java.util.Map;

/**
 * Runnable example: a real-time run driven by three independent heartbeats.
 *
 * <p>Unlike {@link HistoricRunExample} (which replays past-stamped data as fast
 * as it can be merged), this run uses a 30-second window in the near future, so
 * the engine operates in real time: each heartbeat gateway sleeps to its beats'
 * wall-clock times and the engine dispatches from its live queue. The run
 * therefore takes about as long as its window.
 *
 * <p>Three distinct heartbeat streams — at 3 s, 6 s, and 10 s — each on its own
 * topic and gateway, are merged by the engine. Watching the console shows the
 * three cadences interleaving in real time, coinciding at their common multiples
 * (every 30 s all three align; 6 s and 3 s align every 6 s; etc.).
 */
public final class RealTimeHeartbeatExample {

    private static final ComponentReporter LOG =
            new ComponentReporter("Realtime-run", Slf4jReporter.shared());

    private RealTimeHeartbeatExample() {
    }

    public static void main(String[] args) throws Exception {
        final long now   = System.currentTimeMillis();
        // Start a little ahead of "now" so the heartbeats land in the future and
        // the run is wall-clock paced. A cold-start that overshoots startTime is
        // harmless now — mode selection is lenient (a now-inside-window run is
        // REAL_TIME, not the throwing MIXED), so this buffer is for demo timing,
        // not correctness.
        final long start = now + 8_000L;
        final long end   = start + 30_000L;  // 30-second run

        // Three independent heartbeat streams, each its own topic and gateway.
        Topic<HeartBeat> fast   = new Topic<>("heartbeat.3s",  HeartBeat.class);
        Topic<HeartBeat> medium = new Topic<>("heartbeat.6s",  HeartBeat.class);
        Topic<HeartBeat> slow   = new Topic<>("heartbeat.10s", HeartBeat.class);

        MultiClientEngine engine = new MultiClientEngine("Engine", start, end);

        HeartBeatGateway beater3  = new HeartBeatGateway(
                "Beater-3s",  fast,   "3S",   3_000L, start, start, end);
        HeartBeatGateway beater6  = new HeartBeatGateway(
                "Beater-6s",  medium, "6S",   6_000L, start, start, end);
        HeartBeatGateway beater10 = new HeartBeatGateway(
                "Beater-10s", slow,   "10S", 10_000L, start, start, end);

        PrintConsumer printer = new PrintConsumer("Printer");

        engine.registerPublisher(beater3,  fast,   List.of("3S"));
        engine.registerPublisher(beater6,  medium, List.of("6S"));
        engine.registerPublisher(beater10, slow,   List.of("10S"));
        engine.registerActor(printer,
                Map.of(fast,   List.of("3S"),
                       medium, List.of("6S"),
                       slow,   List.of("10S")),
                Map.of());

        Thread engineThread = new Thread(engine, "Engine");
        engineThread.start();
        RunUtils.awaitStatus(engine, GatewayStatus.STARTED, 2_000);

        new Thread(beater3,  "Beater-3s").start();
        new Thread(beater6,  "Beater-6s").start();
        new Thread(beater10, "Beater-10s").start();

        engineThread.join(40_000);
        LOG.info("finished — engine status: " + engine.status());
    }
}
