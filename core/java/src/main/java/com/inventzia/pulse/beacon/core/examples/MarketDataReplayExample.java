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
import com.inventzia.pulse.data.schemas.marketdata.CdfBar;
import com.inventzia.pulse.data.schemas.platform.HeartBeat;
import java.util.List;
import java.util.Map;

/**
 * Runnable example: replay a market-data file of {@link CdfBar} records, with a
 * periodic heartbeat mixed in.
 *
 * <p>Two purposes:
 * <ul>
 *   <li><b>Serialization.</b> {@code CdfBar} carries an {@link java.time.Instant},
 *       a {@link java.time.LocalDate}, and {@link java.math.BigDecimal} prices —
 *       field types a bare {@code ObjectMapper} cannot handle. The reader takes
 *       no serializer argument; it uses pulse-data's shared {@code DatumCodec}.</li>
 *   <li><b>Heartbeats.</b> Four one-minute bars (at 0, 60, 120, 180 s) are merged
 *       with a 30-second {@link HeartBeat}. Both gateways drive the clock, so the
 *       time machine interleaves them in event-time order — showing the heartbeat
 *       keeping time moving between the sparser market-data bars.</li>
 * </ul>
 */
public final class MarketDataReplayExample {

    private static final ComponentReporter LOG =
            new ComponentReporter("marketdata-run", Slf4jReporter.shared());

    private MarketDataReplayExample() {
    }

    public static void main(String[] args) throws Exception {
        final long start = 1_283_630_000_000L;
        final long end   = start + 200_000L;   // covers the 4 bars (last at +180 s)

        Topic<CdfBar>    bars      = new Topic<>("market.data.bar", CdfBar.class);
        Topic<HeartBeat> heartbeat = new Topic<>("heartbeat",      HeartBeat.class);

        MultiClientEngine engine = new MultiClientEngine("Engine", start, end);

        // Market-data reader: four one-minute bars.
        JsonlReaderGateway<CdfBar> reader = new JsonlReaderGateway<>(
                "Bar-reader", bars, List.of("ACME"),
                RunUtils.resource("/examples/data/cdf_bars.jsonl"), start, end);

        // Heartbeat every 30 seconds, from the start of the window.
        HeartBeatGateway beater = new HeartBeatGateway(
                "Heartbeater", heartbeat, "BEAT", 30_000L, start, start, end);

        PrintConsumer printer = new PrintConsumer("Printer");

        engine.registerPublisher(reader, bars,      List.of("ACME"));
        engine.registerPublisher(beater, heartbeat, List.of("BEAT"));
        engine.registerActor(printer,
                Map.of(bars, List.of("ACME"), heartbeat, List.of("BEAT")),
                Map.of());

        Thread engineThread = new Thread(engine, "Engine");
        engineThread.start();
        RunUtils.awaitStatus(engine, GatewayStatus.STARTED, 2_000);

        new Thread(reader, "Bar-reader").start();
        new Thread(beater, "Heartbeater").start();

        engineThread.join(10_000);
        LOG.info("finished — engine status: " + engine.status());
    }
}
