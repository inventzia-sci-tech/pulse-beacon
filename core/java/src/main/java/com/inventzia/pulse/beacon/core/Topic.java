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
 * Routing identity for a stream of data on the bus.
 *
 * <p>A Topic pairs a human-readable {@code name} with the concrete
 * {@link Datum} type carried on it. The type parameter {@code P} lets the bus
 * expose typed publish/subscribe signatures, eliminating casts at most call
 * sites; the engine recovers the concrete type at its single dispatch point
 * via {@link #payloadType()}.
 *
 * <p>Routing semantics (key and logical time) come from the payload itself
 * through the {@link Datum} contract — the Topic carries only the channel
 * name and the payload class.
 *
 * @param <P> the payload type carried on this topic
 */
public record Topic<P extends Datum>(String name, Class<P> payloadType) {

    public Topic {
        Objects.requireNonNull(name, "Topic name must not be null");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Topic name must not be empty");
        }
        Objects.requireNonNull(payloadType, "Topic payloadType must not be null");
    }
}
