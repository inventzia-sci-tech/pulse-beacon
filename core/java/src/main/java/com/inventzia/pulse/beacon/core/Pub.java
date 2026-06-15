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

/**
 * Publishes data to the bus.
 *
 * <p>A Pub holds a reference returned by the engine when it registers as a
 * publisher for one or more {@link Topic}s. Implementations call
 * {@link #publish(Topic, Datum)} to send data; the engine routes each value
 * to all subscribers of the topic.
 *
 * <p><b>Ownership convention.</b> Calling {@code publish} transfers ownership
 * of the payload reference to the bus. The publisher must not retain or mutate
 * the payload after calling {@code publish}. Generated data types are
 * immutable, so this is enforced by the type system in practice.
 *
 * <p>Exceptions thrown by {@code publish} (for example: unknown topic,
 * publisher not registered) are unchecked. The engine's dispatch layer is
 * expected to catch and report them; producers may also catch locally when
 * they have a meaningful recovery path.
 */
public interface Pub {

    /**
     * Publish a payload on a topic.
     *
     * @param topic   the topic to publish on; must match the publisher's
     *                registered topics on the engine
     * @param payload the value to publish; ownership is transferred to the bus
     * @param <P>     the payload type carried by the topic
     */
    <P extends Datum> void publish(Topic<P> topic, P payload);
}
