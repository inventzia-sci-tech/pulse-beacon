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

/**
 * Thrown when a component publishes an event whose event time is earlier than the
 * simulation time currently being dispatched — a retroactive (acausal) publish.
 *
 * <p>Allowing past-time publishes would let a derived event jump ahead of work the
 * engine has already done, making compressed-time replay non-deterministic and
 * diverging from a live run. Same-tick derived events (equal event time) and
 * future events are permitted; only strictly-earlier publishes are rejected.
 */
public class CausalityException extends RuntimeException {

    public CausalityException(String message) {
        super(message);
    }
}
