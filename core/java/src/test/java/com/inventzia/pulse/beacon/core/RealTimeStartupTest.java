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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-time startup regression: the event at exactly {@code startTime} must be
 * delivered, not dropped by a startup race.
 *
 * <p>Previously REAL_TIME posted a {@code STARTUP} command onto the live queue to
 * flip {@code startUpDone}; because that queue is arrival-ordered, an event
 * arriving at the same instant could be dequeued first and dropped while
 * {@code startUpDone} was still false. Startup is now eager (run before the
 * dispatch loop, as in compressed time), so the boundary beat is always seen.
 *
 * <p>The window straddles "now" ({@code start} just in the past), which also
 * exercises the lenient mode selection — a now-inside-window run is REAL_TIME
 * rather than the unimplemented MIXED that used to throw. The first beat is
 * therefore past-due and emitted immediately, making the test fast and
 * deterministic (no dependence on wall-clock scheduling).
 */
class RealTimeStartupTest {

    @RepeatedTest(5)
    void deliversTheBeatAtStartTimeWithoutDroppingIt() throws Exception {
        long now   = System.currentTimeMillis();
        long start = now - 50;     // inside the window now -> REAL_TIME (lenient mode)
        long end   = now + 600;

        Topic<HeartBeat> hb = new Topic<>("rt.heartbeat", HeartBeat.class);
        MultiClientEngine engine = new MultiClientEngine("engine", start, end);
        // First beat at exactly startTime (past-due -> emitted immediately).
        HeartBeatGateway beater = new HeartBeatGateway("rt-beater", hb, "BEAT", 200L, start, start, end);
        CapturingConsumer actor = new CapturingConsumer("capture");

        engine.registerPublisher(beater, hb, List.of("BEAT"));
        engine.registerActor(actor, Map.of(hb, List.of("BEAT")), Map.of());

        Thread engineThread = new Thread(engine, "engine");
        engineThread.start();
        RunUtils.awaitStatus(engine, GatewayStatus.STARTED, 2_000);
        new Thread(beater, "rt-beater").start();
        engineThread.join(5_000);

        assertThat(engine.operatingMode()).isEqualTo(OperatingMode.REAL_TIME);
        // The boundary beat at startTime is the first event delivered — not lost.
        assertThat(actor.times).isNotEmpty();
        assertThat(actor.times.get(0)).isEqualTo(start);
        assertThat(engine.status()).isIn(GatewayStatus.STOPPED, GatewayStatus.COMPLETE);
    }

    private static final class CapturingConsumer extends AbstractActor {
        final List<Long> times = Collections.synchronizedList(new ArrayList<>());

        CapturingConsumer(String name) { super(name); }

        @Override
        public <P extends Datum> void onEvent(Topic<P> topic, P payload) {
            times.add(payload.getDatumTime());
        }
    }
}
