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
 * Lifecycle status of a {@link Gateway}.
 *
 * <p>Ported from the integer status constants of the original platform. The
 * ordinal progression is the normal lifecycle path:
 *
 * <pre>
 *   BLANK -&gt; INITIALIZED -&gt; PRESTART -&gt; STARTED -&gt; WRAP_UP -&gt; FINALIZE
 *         -&gt; STOPPED -&gt; COMPLETE
 * </pre>
 *
 * with {@link #PAUSED} as a side state reachable from {@link #STARTED}.
 */
public enum GatewayStatus {
    /** Constructed but not yet initialized. */
    BLANK,
    /** {@code initialize()} has completed. */
    INITIALIZED,
    /** Connected; about to start clients. */
    PRESTART,
    /** Running and accepting events. */
    STARTED,
    /** End condition reached; draining remaining events. */
    WRAP_UP,
    /** Temporarily suspended. */
    PAUSED,
    /** Past end time; new events are dropped. */
    FINALIZE,
    /** Stopped; no further processing. */
    STOPPED,
    /** Fully complete and torn down. */
    COMPLETE
}
