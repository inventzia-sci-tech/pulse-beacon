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
 * The terminal outcome of an engine run, delivered to a {@link RunListener#onRunTerminated}.
 *
 * <p>This is the <em>engine's</em> outcome, deliberately distinct from any recorder's outcome: a run
 * can complete cleanly while its recorder fails, or fail while its recorder drains cleanly. The two
 * are reported on separate manifest fields ({@code runStatus} vs {@code recordingStatus}); this record
 * carries only the engine side.
 *
 * @param terminalStatus the engine's final {@link GatewayStatus} ({@link GatewayStatus#COMPLETE} on a
 *                        clean finish; {@link GatewayStatus#STOPPED} on failure or interruption)
 * @param error          the failure that ended the run, or {@code null} if it was not a failure
 * @param interrupted    {@code true} if the run ended because its thread was interrupted
 */
public record RunOutcome(GatewayStatus terminalStatus, Throwable error, boolean interrupted) {

    /** @return {@code true} if the engine finished cleanly (completed, no error, not interrupted). */
    public boolean completed() {
        return terminalStatus == GatewayStatus.COMPLETE && error == null && !interrupted;
    }

    /**
     * A short, stable label for the manifest's {@code runStatus}: {@code "completed"}, {@code "failed"}
     * (an error ended it), {@code "aborted"} (interrupted), else {@code "unknown"}.
     *
     * @return the run-status label
     */
    public String runStatus() {
        if (completed())      return "completed";
        if (error != null)    return "failed";
        if (interrupted)      return "aborted";
        return "unknown";
    }
}
