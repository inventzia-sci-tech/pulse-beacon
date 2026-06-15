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

import com.inventzia.pulse.data.datum.Datum;

import java.util.Objects;

/**
 * Base class for in-platform actors (strategies, transformers, consumers).
 *
 * <p>An actor is both a {@link Sub} (it receives events the engine dispatches
 * to it via {@link #onEvent}) and a {@link Pub} (it sends events back onto the
 * bus). Here those two roles are made concrete:
 *
 * <ul>
 *   <li>{@link #onEvent} is left abstract — the subclass handles incoming data,
 *       typically by pattern-matching on the payload type.</li>
 *   <li>{@link #publish} is implemented as the outbound helper: it forwards to
 *       the engine this actor was bound to when it was registered. A subclass
 *       simply calls {@code publish(topic, payload)} to emit.</li>
 * </ul>
 *
 * <h2>Lifecycle</h2>
 * <p>The engine calls {@link #onStartUp} once when the run begins and
 * {@link #onShutDown} once when it ends. Both default to no-ops; override the
 * ones you need. An actor that also implements {@link Runnable} is run on its
 * own daemon thread by the engine (start/stop handled for you).
 *
 * <h2>Binding</h2>
 * <p>An actor cannot publish until it has been registered with an engine —
 * registration binds the engine as this actor's outbound bus. Calling
 * {@link #publish} before then throws {@link IllegalStateException}.
 */
public abstract class AbstractActor implements Actor {

    private final String name;
    private volatile Pub  bus;   // the engine; set once at registration

    /** This actor's logging handle, bound to its name. */
    protected final ComponentReporter log;

    /**
     * @param name human-readable identifier, used in logs and error messages
     */
    protected AbstractActor(String name) {
        this.name = Objects.requireNonNull(name, "name");
        this.log  = new ComponentReporter(name, Slf4jReporter.shared());
    }

    /** Redirects this actor's logging to a different {@link Reporter} sink. */
    protected final void setReporter(Reporter reporter) {
        log.sink(Objects.requireNonNull(reporter, "reporter"));
    }

    /** @return this actor's human-readable name. */
    public final String name() {
        return name;
    }

    // ------------------------------------------------------------------
    // Binding — called by the engine at registration time
    // ------------------------------------------------------------------

    /** Binds the outbound bus (the engine). Package-private: only the engine calls it. */
    final void bind(Pub bus) {
        this.bus = bus;
    }

    // ------------------------------------------------------------------
    // Pub — outbound. Forwards to the bound engine.
    // ------------------------------------------------------------------

    /**
     * Publishes a payload onto the bus via the engine this actor is bound to.
     *
     * @throws IllegalStateException if the actor has not been registered with an engine
     */
    @Override
    public final <P extends Datum> void publish(Topic<P> topic, P payload) {
        Pub b = bus;
        if (b == null) {
            throw new IllegalStateException(
                    "Actor '" + name + "' published before being registered with an engine");
        }
        b.publish(topic, payload);
    }

    // ------------------------------------------------------------------
    // Sub — inbound. Implemented by the subclass.
    // ------------------------------------------------------------------

    @Override
    public abstract <P extends Datum> void onEvent(Topic<P> topic, P payload);

    // ------------------------------------------------------------------
    // Lifecycle hooks — override as needed
    // ------------------------------------------------------------------

    /**
     * Called once when the engine starts the run, at the start time.
     * Default: no-op.
     *
     * @param timeMillis epoch millis of the run start
     */
    protected void onStartUp(long timeMillis) {
        // no-op by default
    }

    /**
     * Called once when the engine ends the run.
     * Default: no-op.
     *
     * @param timeMillis epoch millis of the last processed event
     */
    protected void onShutDown(long timeMillis) {
        // no-op by default
    }
}
