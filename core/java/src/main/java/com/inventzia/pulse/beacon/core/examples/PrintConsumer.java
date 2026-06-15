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

import com.inventzia.pulse.beacon.core.AbstractActor;
import com.inventzia.pulse.beacon.core.Topic;
import com.inventzia.pulse.data.datum.Datum;

/**
 * Example: the simplest consuming actor — it prints each event it receives and
 * logs its own start-up and shut-down.
 *
 * <p>Demonstrates the in-platform actor surface:
 * <ul>
 *   <li>{@link #onEvent} — handle an incoming payload (here: print it).</li>
 *   <li>{@link #onStartUp} / {@link #onShutDown} — lifecycle callbacks the
 *       engine invokes around the run.</li>
 * </ul>
 *
 * <p>An actor that needs its own thread would additionally implement
 * {@link Runnable}; this one is purely reactive and runs on the engine's
 * dispatch thread.
 */
public final class PrintConsumer extends AbstractActor {

    private long count = 0;

    public PrintConsumer(String name) {
        super(name);
    }

    @Override
    protected void onStartUp(long timeMillis) {
        log.info("starting up @ " + timeMillis);
    }

    @Override
    protected void onShutDown(long timeMillis) {
        log.info("shutting down @ " + timeMillis + " (received " + count + " events)");
    }

    @Override
    public <P extends Datum> void onEvent(Topic<P> topic, P payload) {
        count++;
        log.info("#" + count + " " + topic.name() + ": " + payload);
    }
}
