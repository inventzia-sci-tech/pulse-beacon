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
package com.inventzia.beacon.core;

import java.util.Objects;

/**
 * Internal engine control signal, injected onto the event bus to drive
 * lifecycle transitions.
 *
 * <p>{@code EngineCommand} implements {@link Event} so it can be routed
 * through the same {@link TimeMachine} and dispatch path as domain events,
 * preserving causal ordering. It is never codegen'd from a YAML schema and
 * is never exposed to actors or external gateways.
 *
 * <p>The engine uses two commands:
 * <ul>
 *   <li>{@link Kind#STARTUP} — triggers {@code startUpActors()} when the
 *       configured start time is reached.</li>
 *   <li>{@link Kind#SHUTDOWN} — triggers the wrap-up and stop sequence when
 *       end time is reached or all publishers have disconnected.</li>
 * </ul>
 */
record EngineCommand(Kind kind, long eventTime) implements Event {

    /** The two engine lifecycle signals. */
    enum Kind { STARTUP, SHUTDOWN }

    /** Stable type identifier for routing and deserialization. */
    public static final String TYPE_ID = "com.inventzia.beacon.core.EngineCommand";

    EngineCommand {
        Objects.requireNonNull(kind, "kind must not be null");
    }

    @Override public String typeId()        { return TYPE_ID; }
    @Override public int    schemaVersion() { return 1; }
    @Override public String key()           { return ""; }
    @Override public long   publishedTime() { return eventTime; }
    @Override public long   receivedTime()  { return eventTime; }
}
