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

/**
 * Receives events from the bus.
 *
 * <p>A Sub registers with the engine for one or more {@link Topic}s, and the
 * engine calls {@link #onEvent(Topic, Event)} for each event delivered on
 * those topics.
 *
 * <p><b>Ownership convention.</b> The event reference passed to
 * {@code onEvent} is borrowed; the bus owns the original. Subscribers may
 * read the event freely during the call, but if they need to retain it past
 * the callback they should treat the reference as borrowed. Generated event
 * types are immutable, so retention is safe without copying.
 *
 * <p>Exceptions thrown by {@code onEvent} are unchecked. The engine's
 * dispatch loop catches and reports them so a misbehaving subscriber does
 * not crash the bus.
 */
public interface Sub {

    /**
     * Handle an event delivered on a topic.
     *
     * @param topic the topic on which the event was delivered
     * @param event the event payload
     * @param <E>   the event type carried by the topic
     */
    <E extends Event> void onEvent(Topic<E> topic, E event);
}
