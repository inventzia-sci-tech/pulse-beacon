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
 * Base type for anything that travels on the pulse-beacon event bus.
 *
 * <p>Concrete event classes are generated from YAML schemas (see pulse-data)
 * and implement this interface. The core platform never instantiates events
 * directly; it routes them by topic and timestamps them as they cross
 * gateways.
 *
 * <p>Each event carries three timestamps (epoch milliseconds):
 * <ul>
 *   <li>{@link #eventTime()} — wall-clock time the event represents.</li>
 *   <li>{@link #publishedTime()} — when the producer published it onto the bus.</li>
 *   <li>{@link #receivedTime()} — when the local consumer received it.</li>
 * </ul>
 *
 * <p>Ownership convention: a publisher hands an Event to the bus and transfers
 * ownership. A subscriber that retains the reference past the callback is safe
 * because generated event types are immutable.
 *
 * <p><b>Codegen contract.</b> In addition to implementing this interface,
 * every concrete Event class must declare
 * {@code public static final String TYPE_ID} whose value equals
 * {@link #typeId()} for any instance of that class. This lets
 * {@link Topic} resolve the typeId from the class alone, without needing an
 * instance. Java interfaces cannot enforce static members, so this is a
 * convention guaranteed by codegen and validated at {@link Topic}
 * construction time.
 */
public interface Event {

    /**
     * Stable type identifier for this event, generated at build time from the
     * schema {@code $id}. Used for routing and to select the right
     * (de)serializer.
     */
    String typeId();

    /**
     * Schema version of this event type, generated from the YAML
     * {@code eventVersion} field. Engines and gateways use this for
     * compatibility checks across producers and consumers built at different
     * times.
     */
    int schemaVersion();

    /**
     * Instance key for routing within a topic (for example a symbol, a session
     * id, a tenant). Required; return an empty string when no specific key
     * applies.
     */
    String key();

    /** Epoch milliseconds: when the event happened. */
    long eventTime();

    /** Epoch milliseconds: when the producer published this event. */
    long publishedTime();

    /** Epoch milliseconds: when the local consumer received this event. */
    long receivedTime();
}
