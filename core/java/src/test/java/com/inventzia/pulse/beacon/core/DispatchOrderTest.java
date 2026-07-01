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
 * Actor dispatch order is deterministic: for a given {@code (topic, key)},
 * high-priority actors are dispatched first, then normal-priority, each pass in
 * registration order — never in {@code HashSet} bucket order.
 *
 * <p>A single trigger beat fans out to several actors registered on the same
 * key; each records its label as it is dispatched. {@link RepeatedTest} guards
 * that the order is stable run-to-run (identity-hash bucket order would vary).
 */
class DispatchOrderTest {

    private static final long START   = 1_000_000L;
    private static final long END     = 1_010_000L;
    private static final long TRIGGER = 1_001_000L; // period > window ⇒ exactly one beat

    private static final Topic<HeartBeat> TRIGGER_TOPIC = new Topic<>("trigger", HeartBeat.class);
    private static final List<String> T = List.of("T");

    @RepeatedTest(10)
    void highPriorityFirstThenNormal_eachInRegistrationOrder() throws Exception {
        List<String> order = runWith(List.of(
                spec("A", false), spec("B", false), spec("C", true),
                spec("D", false), spec("E", true)));
        // high (C, E) in registration order, then normal (A, B, D) in registration order.
        assertThat(order).containsExactly("C", "E", "A", "B", "D");
    }

    @RepeatedTest(10)
    void allNormalPriority_dispatchesInRegistrationOrder() throws Exception {
        // The formerly non-deterministic path (no high-priority actors -> iterated a HashSet).
        List<String> order = runWith(List.of(
                spec("one", false), spec("two", false), spec("three", false), spec("four", false)));
        assertThat(order).containsExactly("one", "two", "three", "four");
    }

    private List<String> runWith(List<Spec> specs) throws InterruptedException {
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        MultiClientEngine engine = new MultiClientEngine("engine", START, END);
        HeartBeatGateway trigger = new HeartBeatGateway(
                "trigger", TRIGGER_TOPIC, "T", 100_000L, TRIGGER, START, END);

        engine.registerPublisher(trigger, TRIGGER_TOPIC, T);
        for (Spec s : specs) {
            engine.registerActor(new RecordingActor(s.label(), order),
                    Map.of(TRIGGER_TOPIC, T), Map.of(), s.highPriority());
        }

        Thread engineThread = new Thread(engine, "engine");
        engineThread.start();
        RunUtils.awaitStatus(engine, GatewayStatus.STARTED, 2_000);
        new Thread(trigger, "trigger").start();
        engineThread.join(5_000);

        assertThat(engine.status()).isIn(GatewayStatus.STOPPED, GatewayStatus.COMPLETE);
        return order;
    }

    private static Spec spec(String label, boolean high) { return new Spec(label, high); }

    private record Spec(String label, boolean highPriority) { }

    /** Records its label onto a shared list each time it is dispatched an event. */
    private static final class RecordingActor extends AbstractActor {
        private final String label;
        private final List<String> order;

        RecordingActor(String label, List<String> order) {
            super(label);
            this.label = label;
            this.order = order;
        }

        @Override
        public <P extends Datum> void onEvent(Topic<P> topic, P payload) {
            order.add(label);
        }
    }
}
