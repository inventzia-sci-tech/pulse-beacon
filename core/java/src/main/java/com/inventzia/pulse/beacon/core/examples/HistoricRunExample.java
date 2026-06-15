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
import com.inventzia.pulse.beacon.core.gateway.file.JsonlReaderGateway;
import com.inventzia.pulse.beacon.core.gateway.periodic.HeartBeatGateway;
import com.inventzia.pulse.data.schemas.platform.HeartBeat;
import com.inventzia.pulse.data.schemas.platform.TextMessage;

import java.util.List;
import java.util.Map;

/**
 * Runnable example: a historical (compressed-time) replay that ties the whole
 * platform together.
 *
 * <p>Two JSONL files are replayed as clock-driving gateways; a heartbeat
 * gateway adds a periodic beat; the merged, time-ordered stream is delivered
 * to two actors — one that prints and one that echoes each event onto its own
 * topic — and the echoes are written to a {@link PrintGateway}.
 *
 * <pre>
 *   reader-1 (stream.one) ─┐
 *   reader-2 (stream.two) ─┼─▶ [ engine / time machine ] ─▶ PrintConsumer
 *   heartbeat ────────────┘                              └▶ EchoConsumer ─▶ (echo topic) ─▶ PrintGateway
 * </pre>
 *
 * <p>Because the fixtures are stamped in 2010, "now" is always past the end of
 * the window, so the run is deterministic and finishes as fast as the data can
 * be merged.
 */
public final class HistoricRunExample {

    private static final ComponentReporter LOG =
            new ComponentReporter("historic-run", Slf4jReporter.shared());

    private HistoricRunExample() {
    }

    public static void main(String[] args) throws Exception {
        final long start = 1_283_630_000_000L;
        final long end   = 1_283_630_007_000L;

        // --- topics ---------------------------------------------------------
        Topic<TextMessage> streamOne = new Topic<>("stream.one", TextMessage.class);
        Topic<TextMessage> streamTwo = new Topic<>("stream.two", TextMessage.class);
        Topic<HeartBeat>   heartbeat = new Topic<>("heartbeat",  HeartBeat.class);
        Topic<TextMessage> echo      = new Topic<>("echo",       TextMessage.class);

        // --- engine ---------------------------------------------------------
        MultiClientEngine engine = new MultiClientEngine("engine", start, end);

        // --- producing gateways (all clock-driving) -------------------------
        JsonlReaderGateway<TextMessage> readerOne = new JsonlReaderGateway<>(
                "reader-1", streamOne, List.of("A"),
                RunUtils.resource("/examples/data/messages_one.jsonl"), start, end);
        JsonlReaderGateway<TextMessage> readerTwo = new JsonlReaderGateway<>(
                "reader-2", streamTwo, List.of("A"),
                RunUtils.resource("/examples/data/messages_two.jsonl"), start, end);
        HeartBeatGateway beater = new HeartBeatGateway(
                "beater", heartbeat, "BEAT",
                2_000L, start + 1_500L, start, end);

        // --- actors ---------------------------------------------------------
        PrintConsumer printConsumer = new PrintConsumer("print-actor");
        EchoConsumer  echoConsumer  = new EchoConsumer("echo-actor", echo, "ECHO");

        // --- a sink gateway for the echoes ----------------------------------
        PrintGateway echoSink = new PrintGateway("echo-sink", start, end);

        // --- registration ---------------------------------------------------
        engine.registerPublisher(readerOne, streamOne, List.of("A"));
        engine.registerPublisher(readerTwo, streamTwo, List.of("A"));
        engine.registerPublisher(beater,    heartbeat, List.of("BEAT"));

        engine.registerActor(printConsumer,
                Map.of(streamOne, List.of("A"),
                       streamTwo, List.of("A"),
                       heartbeat, List.of("BEAT")),
                Map.of());
        engine.registerActor(echoConsumer,
                Map.of(streamOne, List.of("A")),
                Map.of(echo, List.of("ECHO")));

        engine.registerSubscriber(echoSink, echo, List.of("ECHO"));

        // --- run: engine first, then release the producers ------------------
        Thread engineThread = new Thread(engine, "engine");
        engineThread.start();
        RunUtils.awaitStatus(engine, GatewayStatus.STARTED, 2_000);

        Thread t1 = new Thread(readerOne, "reader-1");
        Thread t2 = new Thread(readerTwo, "reader-2");
        Thread t3 = new Thread(beater,    "beater");
        Thread t4 = new Thread(echoSink,  "echo-sink");
        t1.start();
        t2.start();
        t3.start();
        t4.start();

        engineThread.join(10_000);
        LOG.info("finished — engine status: " + engine.status());
    }
}
