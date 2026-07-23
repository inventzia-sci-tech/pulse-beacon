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

import com.inventzia.pulse.beacon.core.crosslanguage.CrossLanguageException;
import com.inventzia.pulse.beacon.core.crosslanguage.CrossLanguageGateway;
import com.inventzia.pulse.beacon.core.examples.RunUtils;
import com.inventzia.pulse.beacon.core.gateway.file.JsonlReaderGateway;
import com.inventzia.pulse.data.datum.Datum;
import com.inventzia.pulse.data.schemas.platform.HeartBeat;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression tests for the failure-path contract of clock-driving gateways: when a
 * source gateway's own thread dies (bad input), it must still {@code disconnect()} so
 * the {@link TimeMachine} all-drivers barrier is released and the engine terminates
 * <em>fast</em> — rather than hanging until an external timeout on a driver that will
 * never contribute another event.
 *
 * <p>Before the fix, an exception thrown out of a gateway's {@code run()} skipped
 * {@code disconnect()}, leaving the gateway registered as a clock driver forever.
 */
class GatewayFailureTest {

    private static final long START = 1_283_630_000_000L;
    private static final long END   = 1_283_630_007_000L;

    /** H1: a corrupt line in a JSONL reader must not hang the run. */
    @Test
    void jsonlReaderThatHitsACorruptLineDisconnectsAndTheRunCompletes() throws Exception {
        // A valid beat, then a line the codec cannot parse.
        Path file = Files.createTempFile("gateway-failure", ".jsonl");
        file.toFile().deleteOnExit();
        Files.write(file, List.of(
                "{\"beatKey\":\"BEAT\",\"beatTime\":" + (START + 1_000) + "}",
                "this is not valid json"));

        Topic<HeartBeat> heartbeat = new Topic<>("heartbeat", HeartBeat.class);
        MultiClientEngine engine = new MultiClientEngine("engine", START, END);
        JsonlReaderGateway<HeartBeat> reader =
                new JsonlReaderGateway<>("Reader", heartbeat, List.of("BEAT"), file, START, END);
        Capturer capturer = new Capturer("cap");

        engine.registerPublisher(reader, heartbeat, List.of("BEAT"));
        engine.registerActor(capturer, Map.of(heartbeat, List.of("BEAT")), Map.of());

        Thread engineThread = new Thread(engine, "engine");
        engineThread.start();
        RunUtils.awaitStatus(engine, GatewayStatus.STARTED, 2_000);
        new Thread(reader, "reader").start();

        engineThread.join(5_000);

        assertThat(engineThread.isAlive()).as("engine did not hang on the dead driver").isFalse();
        assertThat(engine.status()).isIn(GatewayStatus.STOPPED, GatewayStatus.COMPLETE);
        assertThat(reader.status()).as("failed reader disconnected").isEqualTo(GatewayStatus.STOPPED);
        // The one valid beat before the corrupt line was still delivered.
        assertThat(capturer.times).containsExactly(START + 1_000);
    }

    /** H2: a cross-language source fed malformed tagged JSON must not hang the run. */
    @Test
    void crossLanguageSourceFedBadJsonDisconnectsAndTheRunCompletes() throws Exception {
        Topic<HeartBeat> heartbeat = new Topic<>("heartbeat", HeartBeat.class);
        MultiClientEngine engine = new MultiClientEngine("engine", START, END);
        CrossLanguageGateway source =
                new CrossLanguageGateway("py-src", START, END).withTopic(heartbeat);

        engine.registerPublisher(source, heartbeat, List.of("BEAT"));

        Thread engineThread = new Thread(engine, "engine");
        engineThread.start();
        RunUtils.awaitStatus(engine, GatewayStatus.STARTED, 2_000);
        Thread sourceThread = new Thread(source, "py-src");
        sourceThread.start();

        // The foreign producer offers one malformed envelope; the source loop fails
        // decoding it and unwinds. offerNext still returns (the failed emission releases
        // its ack), but the source is now closed, so a subsequent offer must fail fast
        // rather than block forever on a rendezvous no consumer will complete.
        source.offerNext("heartbeat", "{ this is not valid tagged json");
        assertThatThrownBy(() -> source.offerNext("heartbeat", "{\"whatever\":1}"))
                .isInstanceOf(CrossLanguageException.class);

        engineThread.join(5_000);
        sourceThread.join(5_000);

        assertThat(engineThread.isAlive()).as("engine did not hang on the dead source").isFalse();
        assertThat(engine.status()).isIn(GatewayStatus.STOPPED, GatewayStatus.COMPLETE);
        assertThat(source.status()).as("failed source disconnected").isEqualTo(GatewayStatus.STOPPED);
    }

    /** Records the event times it receives, for the H1 partial-delivery assertion. */
    private static final class Capturer extends AbstractActor {
        final List<Long> times = Collections.synchronizedList(new ArrayList<>());

        Capturer(String name) { super(name); }

        @Override
        public <P extends Datum> void onEvent(Topic<P> topic, P payload) {
            times.add(payload.getDatumTime());
        }
    }
}
