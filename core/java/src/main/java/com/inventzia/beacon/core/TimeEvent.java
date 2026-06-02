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
 * The internal transport quantum of the pulse-beacon engine.
 *
 * <p>A {@code TimeEvent} wraps a single {@link Event} with the routing and
 * timing context the engine needs to order, dispatch, and account for it.
 * It is an engine-internal type: subscribers receive the unwrapped
 * {@link Event} via {@link Sub#onEvent}; {@code TimeEvent} is never part
 * of the public actor API.
 *
 * <p>{@link #topic()} uses a wildcard ({@code Topic<?>}) because the engine's
 * internal queue is heterogeneous — it holds events of many types simultaneously.
 * Type safety is recovered at the dispatch site, where the engine casts once
 * using {@link Topic#eventType()} before calling {@link Sub#onEvent}.
 *
 * <p>{@link #origin()} is required by the {@code TimeMachine}: it maintains a
 * per-gateway lock map and a separate map of the latest event from each
 * clock-driving gateway. Both maps are keyed on the originating gateway.
 *
 * <p>Timestamps:
 * <ul>
 *   <li>{@link #beginTstamp()} — epoch millis when the gateway first received
 *       or produced the underlying data (wall-clock at read time).</li>
 *   <li>{@link #readTstamp()} — epoch millis when the event was placed into
 *       the {@code TimeMachine} queue.</li>
 * </ul>
 */
public record TimeEvent(
        Event    event,
        Topic<?> topic,
        Gateway  origin,
        long     beginTstamp,
        long     readTstamp
) {
    public TimeEvent {
        Objects.requireNonNull(event,  "event must not be null");
        Objects.requireNonNull(topic,  "topic must not be null");
        Objects.requireNonNull(origin, "origin must not be null");
    }
}
