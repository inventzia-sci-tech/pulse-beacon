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
 * Describes how a {@link Gateway} relates to wall-clock time.
 *
 * <p>The engine uses this to decide how to schedule and pace event delivery.
 * A gateway determines its own mode at initialisation time by comparing the
 * current wall clock against its configured start and end times.
 *
 * <pre>
 *   now &lt; startTime                → REAL_TIME    (future window, follow the clock)
 *   startTime ≤ now ≤ endTime      → MIXED        (window straddles now)
 *   now &gt; endTime                  → COMPRESSED_TIME (historical replay, run as fast as possible)
 * </pre>
 */
public enum OperatingMode {

    /** Mode has not been determined yet. */
    UNDEFINED,

    /**
     * The gateway's window lies entirely in the future; events are delivered
     * in sync with wall-clock time.
     */
    REAL_TIME,

    /**
     * The gateway's window straddles the current wall-clock time: part of
     * the data is historical (replayed at full speed) and part is live
     * (paced by the clock).
     */
    MIXED,

    /**
     * The gateway's window lies entirely in the past; events are delivered
     * as fast as the engine can process them.
     */
    COMPRESSED_TIME
}
