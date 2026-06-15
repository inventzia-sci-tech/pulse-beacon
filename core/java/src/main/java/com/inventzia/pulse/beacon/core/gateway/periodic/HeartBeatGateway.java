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
package com.inventzia.pulse.beacon.core.gateway.periodic;

import com.inventzia.pulse.beacon.core.AbstractGateway;
import com.inventzia.pulse.beacon.core.Gateway;
import com.inventzia.pulse.beacon.core.GatewayStatus;
import com.inventzia.pulse.beacon.core.OperatingMode;
import com.inventzia.pulse.beacon.core.Topic;
import com.inventzia.pulse.data.datum.Datum;
import com.inventzia.pulse.data.schemas.platform.HeartBeat;

import java.util.Objects;

/**
 * A clock-driving gateway that emits a periodic {@link HeartBeat} on a topic.
 *
 * <p>Heartbeats give actors a regular "time has passed" signal so they can run
 * periodic behaviour (analytics windows, timeout checks) even when no domain
 * data arrives in an interval. This gateway is entirely self-contained: it
 * needs no special engine support and works identically in both operating
 * modes, differing only in how it paces itself.
 *
 * <h2>Compressed time (historical replay)</h2>
 * <p>The gateway loops over its beat schedule and publishes each beat through
 * the {@code TimeMachine}. Because it drives the clock, each publish blocks on
 * the time machine's per-gateway write permit until the previous beat has been
 * consumed. The beats therefore interleave, in event-time order, with the
 * events of every other clock-driving gateway — no wall-clock timer, no
 * parasitic injection.
 *
 * <h2>Real time</h2>
 * <p>The gateway sleeps on its own thread until each beat's wall-clock time,
 * then publishes. In real-time mode the engine dispatches from its live queue,
 * so the publish does not block.
 *
 * <h2>Lifecycle</h2>
 * <p>The gateway runs on its own thread (started by the caller). It emits beats
 * from {@code firstBeatTime}, stepping by {@code periodMillis}, while the beat
 * time is within {@code [.., endTime]} and the gateway remains connected. When
 * the schedule is exhausted it disconnects, which signals the engine that this
 * clock source is done.
 */
public final class HeartBeatGateway extends AbstractGateway {

    private final Topic<HeartBeat> topic;
    private final String           key;
    private final long             periodMillis;
    private final long             firstBeatTime;

    /**
     * @param name          human-readable name for this gateway instance
     * @param topic         the topic on which heartbeats are published
     * @param key           the beat key (routing key carried by each {@link HeartBeat})
     * @param periodMillis  interval between beats, in milliseconds (must be &gt; 0)
     * @param firstBeatTime epoch millis of the first beat
     * @param startTime     epoch millis for the start of this gateway's window
     * @param endTime       epoch millis for the end of this gateway's window;
     *                      no beat is emitted after this time
     */
    public HeartBeatGateway(String name,
                            Topic<HeartBeat> topic,
                            String key,
                            long periodMillis,
                            long firstBeatTime,
                            long startTime,
                            long endTime) {
        super(name, startTime, endTime);
        this.topic         = Objects.requireNonNull(topic, "topic");
        this.key           = Objects.requireNonNull(key,   "key");
        if (periodMillis <= 0) {
            throw new IllegalArgumentException("periodMillis must be > 0, was " + periodMillis);
        }
        this.periodMillis  = periodMillis;
        this.firstBeatTime = firstBeatTime;
        setDriveClock(true);
    }

    // ------------------------------------------------------------------
    // Runnable — the beat loop
    // ------------------------------------------------------------------

    @Override
    public void run() {
        initialize();
        connect();
        setStatus(GatewayStatus.STARTED);

        boolean realTime = operatingMode() == OperatingMode.REAL_TIME;

        try {
            for (long t = firstBeatTime; t <= endTime() && connected(); t += periodMillis) {
                if (realTime) {
                    long wait = t - System.currentTimeMillis();
                    if (wait > 0) {
                        Thread.sleep(wait);
                    }
                    if (!connected()) break;
                }

                Gateway downstream = subscriberForKey(topic, key);
                if (downstream != null) {
                    // In compressed time this blocks on the time-machine permit
                    // until the previous beat is consumed, pacing the loop.
                    downstream.onEvent(topic, new HeartBeat(key, t));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        disconnect();
        setStatus(GatewayStatus.STOPPED);
    }

    // ------------------------------------------------------------------
    // Pub / Sub — heartbeat gateway produces, never receives
    // ------------------------------------------------------------------

    @Override
    public <P extends Datum> void publish(Topic<P> topic, P payload) {
        throw new UnsupportedOperationException(
                name() + ": HeartBeatGateway does not accept publish() calls");
    }

    @Override
    public <P extends Datum> void onEvent(Topic<P> topic, P payload) {
        throw new UnsupportedOperationException(
                name() + ": HeartBeatGateway does not accept incoming events");
    }
}
