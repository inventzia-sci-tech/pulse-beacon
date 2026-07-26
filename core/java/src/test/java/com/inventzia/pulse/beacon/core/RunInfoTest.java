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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The public {@link AbstractEngine#runInfo()} observer API: the sanctioned way for a
 * run orchestrator to record a run's mode and window without reaching into the
 * protected {@code operatingMode()} accessor. (Actors never get here — they hold only
 * a {@code Pub}.)
 */
class RunInfoTest {

    private static final long START = 1_283_630_000_000L; // window entirely in the past
    private static final long END   = 1_283_630_005_000L;

    @Test
    void runInfoReportsSelectedModeAndWindow() throws Exception {
        Topic<HeartBeat> hb = new Topic<>("hb", HeartBeat.class);
        MultiClientEngine engine = new MultiClientEngine("engine", START, END);
        HeartBeatGateway beater =
                new HeartBeatGateway("beater", hb, "BEAT", 2_000L, START + 1_000L, START, END);
        engine.registerPublisher(beater, hb, List.of("BEAT"));
        engine.registerActor(new Sink("sink"), Map.of(hb, List.of("BEAT")), Map.of());

        // Before initialization the mode is UNDEFINED (window is still readable).
        assertThat(engine.runInfo().mode()).isEqualTo(OperatingMode.UNDEFINED);

        Thread engineThread = new Thread(engine, "engine");
        engineThread.start();
        RunUtils.awaitStatus(engine, GatewayStatus.STARTED, 2_000);
        new Thread(beater, "beater").start();
        engineThread.join(5_000);

        RunInfo info = engine.runInfo();
        assertThat(info.mode()).isEqualTo(OperatingMode.COMPRESSED_TIME); // window is in the past
        assertThat(info.startTime()).isEqualTo(START);
        assertThat(info.endTime()).isEqualTo(END);
        // A running engine only ever selects one of the two live modes.
        assertThat(info.mode()).isIn(OperatingMode.COMPRESSED_TIME, OperatingMode.REAL_TIME);
    }

    /** Minimal actor so the engine has a consumer to dispatch to. */
    private static final class Sink extends AbstractActor {
        Sink(String name) { super(name); }

        @Override public <P extends Datum> void onEvent(Topic<P> topic, P payload) { }
    }
}
