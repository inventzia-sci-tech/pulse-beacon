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
import com.inventzia.pulse.data.schemas.platform.TextMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The causality policy for actor publishes: an actor may publish a derived event
 * at or after the simulation time it is currently handling (same-tick and future),
 * but a publish with an <em>earlier</em> event time is acausal and rejected with a
 * {@link CausalityException}.
 *
 * <p>A single trigger beat at {@code TRIGGER} drives a republisher actor, which
 * emits a derived event at {@code TRIGGER + offset}. A capturer records what is
 * actually delivered; the republisher counts rejections.
 */
class CausalityTest {

    private static final long START   = 1_000_000L;
    private static final long END     = 1_010_000L;
    private static final long TRIGGER = 1_001_000L; // period > window ⇒ exactly one beat

    private static final Topic<HeartBeat>   TRIGGER_TOPIC = new Topic<>("trigger", HeartBeat.class);
    private static final Topic<TextMessage> DERIVED_TOPIC = new Topic<>("derived", TextMessage.class);
    private static final List<String> T = List.of("T");
    private static final List<String> D = List.of("D");

    @Test
    void rejectsAPastTimePublish() throws Exception {
        Result r = run(-500L); // derived before the current tick → rejected, not delivered
        assertThat(r.rejections()).isEqualTo(1);
        assertThat(r.delivered()).isEmpty();
    }

    @Test
    void allowsASameTickDerivedPublish() throws Exception {
        Result r = run(0L); // derived at the current tick → allowed
        assertThat(r.rejections()).isZero();
        assertThat(r.delivered()).containsExactly(TRIGGER);
    }

    @Test
    void allowsAFuturePublish() throws Exception {
        Result r = run(+500L);
        assertThat(r.rejections()).isZero();
        assertThat(r.delivered()).containsExactly(TRIGGER + 500L);
    }

    private Result run(long offset) throws InterruptedException {
        MultiClientEngine engine = new MultiClientEngine("engine", START, END);
        HeartBeatGateway trigger = new HeartBeatGateway(
                "trigger", TRIGGER_TOPIC, "T", 100_000L, TRIGGER, START, END);
        Republisher republisher = new Republisher("republisher", offset);
        Capturer capturer = new Capturer("capturer");

        engine.registerPublisher(trigger, TRIGGER_TOPIC, T);
        engine.registerActor(republisher, Map.of(TRIGGER_TOPIC, T), Map.of(DERIVED_TOPIC, D));
        engine.registerActor(capturer, Map.of(DERIVED_TOPIC, D), Map.of());

        Thread engineThread = new Thread(engine, "engine");
        engineThread.start();
        RunUtils.awaitStatus(engine, GatewayStatus.STARTED, 2_000);
        new Thread(trigger, "trigger").start();
        engineThread.join(5_000);

        assertThat(engine.status()).isIn(GatewayStatus.STOPPED, GatewayStatus.COMPLETE);
        return new Result(republisher.rejections.get(), capturer.times);
    }

    private record Result(int rejections, List<Long> delivered) { }

    /** On each trigger event, publishes a derived event offset from the trigger's time. */
    private static final class Republisher extends AbstractActor {
        final long offset;
        final AtomicInteger rejections = new AtomicInteger();

        Republisher(String name, long offset) {
            super(name);
            this.offset = offset;
        }

        @Override
        public <P extends Datum> void onEvent(Topic<P> topic, P payload) {
            long derivedTime = payload.getDatumTime() + offset;
            try {
                publish(DERIVED_TOPIC, new TextMessage("D", derivedTime, "derived"));
            } catch (CausalityException e) {
                rejections.incrementAndGet();
            }
        }
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
