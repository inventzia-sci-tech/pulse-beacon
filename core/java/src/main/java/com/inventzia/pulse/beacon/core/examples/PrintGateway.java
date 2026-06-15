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

import com.inventzia.pulse.beacon.core.AbstractGateway;
import com.inventzia.pulse.beacon.core.GatewayStatus;
import com.inventzia.pulse.beacon.core.Topic;
import com.inventzia.pulse.data.datum.Datum;

import java.util.concurrent.CountDownLatch;

/**
 * Example: a subscriber gateway that prints everything delivered to it.
 *
 * <p>This is the simplest possible boundary actor — it consumes events and
 * writes them to stdout. The engine delivers events to it via {@link #onEvent}
 * on the dispatch thread; the gateway's own thread merely keeps it alive
 * (initialised, connected, STARTED) until {@link #disconnect()} is called.
 *
 * <p>It is a pure subscriber, so {@link #publish} is unsupported — gateways
 * that produce data do so by looking up their downstream and calling
 * {@code onEvent} on it (see {@code JsonlReaderGateway}, {@code HeartBeatGateway}).
 */
public final class PrintGateway extends AbstractGateway {

    private final CountDownLatch stop = new CountDownLatch(1);

    public PrintGateway(String name, long startTime, long endTime) {
        super(name, startTime, endTime);
        setDriveClock(false); // a sink never drives the clock
    }

    @Override
    public void run() {
        initialize();
        connect();
        setStatus(GatewayStatus.STARTED);
        try {
            stop.await();                 // parked; events arrive via onEvent()
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        setStatus(GatewayStatus.STOPPED);
    }

    @Override
    public synchronized <P extends Datum> void onEvent(Topic<P> topic, P payload) {
        log.info(topic.name() + " <- " + payload);
    }

    @Override
    public <P extends Datum> void publish(Topic<P> topic, P payload) {
        throw new UnsupportedOperationException(
                name() + ": PrintGateway is a subscriber and does not publish");
    }

    @Override
    public synchronized void disconnect() {
        super.disconnect();
        stop.countDown();
    }
}
