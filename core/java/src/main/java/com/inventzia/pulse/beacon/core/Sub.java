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
 * Receives data from the bus.
 *
 * <p>A Sub registers with the engine for one or more {@link Topic}s, and the
 * engine calls {@link #onEvent(Topic, Datum)} for each value delivered on
 * those topics.
 *
 * <p><b>Ownership convention.</b> The payload reference passed to
 * {@code onEvent} is borrowed; the bus owns the original. Subscribers may
 * read it freely during the call, and since generated data types are
 * immutable, retaining the reference past the callback is safe without copying.
 *
 * <p>Exceptions thrown by {@code onEvent} are unchecked. The engine's
 * dispatch loop catches and reports them so a misbehaving subscriber does
 * not crash the bus.
 */
public interface Sub {

    /**
     * Handle a payload delivered on a topic.
     *
     * @param topic   the topic on which the value was delivered
     * @param payload the value
     * @param <P>     the payload type carried by the topic
     */
    <P extends Datum> void onEvent(Topic<P> topic, P payload);
}
