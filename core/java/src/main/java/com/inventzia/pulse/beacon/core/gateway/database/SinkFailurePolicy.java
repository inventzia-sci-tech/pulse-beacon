/*
 * SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
 * Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.
 *
 * This file is part of pulse-beacon.
 *
 * pulse-beacon is dual-licensed:
 *   - Under the GNU Affero General Public License v3.0 (see LICENSE-AGPL-3.0).
 *   - Under a commercial license (see LICENSE-COMMERCIAL.txt).
 *     Contact operations@inventzia.com.
 */
package com.inventzia.pulse.beacon.core.gateway.database;

/**
 * What a persistence failure means for the run.
 *
 * <p>"Never kill the run" is right for {@code RunRecording}, which records <em>alongside</em> the real
 * output: killing a live trading run because a disk filled would be worse than losing its recording.
 * It is too absolute for a general persistence gateway. An application whose essential output <em>is</em>
 * the database — an ETL job, a reconciliation load — is not doing anything useful once the writes stop,
 * and should be able to say so rather than run to completion producing nothing.
 *
 * <p>So the choice is declared, and the default is the conservative one. See
 * {@code docs/pulse-sql-gateway.md} §7.
 */
public enum SinkFailurePolicy {

    /**
     * The default: persistence failures are recorded in the run manifest and the run continues.
     *
     * <p>For a sink that observes a run whose real output is elsewhere. Failures are never silent —
     * they are counted, logged loudly, and reflected in the run's recording status — they are just not
     * fatal.
     */
    OBSERVATIONAL,

    /**
     * Persistence failures fail the run.
     *
     * <p>For an application whose output <em>is</em> the database. Must be declared explicitly: a sink
     * that can stop a run is not something to acquire by default.
     */
    ESSENTIAL;

    /** Whether a persistence failure under this policy should terminate the run. */
    public boolean isFatal() {
        return this == ESSENTIAL;
    }
}
