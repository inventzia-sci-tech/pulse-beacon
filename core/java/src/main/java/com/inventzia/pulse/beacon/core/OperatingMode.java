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
 * current wall clock against its configured start and end times:
 *
 * <pre>
 *   now &gt; endTime   → COMPRESSED_TIME (window entirely past: replay as fast as possible)
 *   otherwise       → REAL_TIME       (window current or future: follow the clock)
 * </pre>
 *
 * <p>{@link #MIXED} is <b>reserved but not yet selected</b>: a window that
 * straddles now is treated as {@code REAL_TIME} (lenient), with the contract
 * that the engine does not backfill events in {@code [startTime, now]}. See
 * {@code AbstractGateway.initialize()}.
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
     *
     * <p><b>Not yet implemented, and currently never selected.</b> Mode
     * selection is intentionally lenient: a now-inside-window run is treated as
     * {@link #REAL_TIME}, so events in {@code [startTime, now]} are <em>not</em>
     * backfilled — they are seen only if a live source emits them late. This
     * value is reserved for a future true replay-then-live mode that would
     * deterministically replay {@code [startTime, now]} before going live.
     */
    MIXED,

    /**
     * The gateway's window lies entirely in the past; events are delivered
     * as fast as the engine can process them.
     */
    COMPRESSED_TIME
}
