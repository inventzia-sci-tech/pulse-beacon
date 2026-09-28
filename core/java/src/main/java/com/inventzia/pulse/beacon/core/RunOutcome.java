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

import java.util.List;

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
public record RunOutcome(GatewayStatus terminalStatus, Throwable error, boolean interrupted,
                         List<GatewayFailure> gatewayFailures) {

    public RunOutcome {
        gatewayFailures = gatewayFailures == null ? List.of() : List.copyOf(gatewayFailures);
    }

    /** A run that ended without any gateway reporting a terminal failure. */
    public RunOutcome(GatewayStatus terminalStatus, Throwable error, boolean interrupted) {
        this(terminalStatus, error, interrupted, List.of());
    }

    /**
     * Gateways whose failure was declared fatal to the run.
     *
     * <p>These are why {@link #runStatus()} says {@code failed}, and naming them is the difference
     * between a manifest an operator can act on and one that only says something went wrong.
     */
    public List<GatewayFailure> fatalFailures() {
        return gatewayFailures.stream().filter(GatewayFailure::fatal).toList();
    }

    /** Names of the gateways that failed fatally, for the manifest. */
    public List<String> failedGateways() {
        return fatalFailures().stream().map(GatewayFailure::gatewayName).distinct().toList();
    }

    /**
     * @return {@code true} if the engine finished cleanly: completed, no error, not interrupted, and
     *         no gateway failed fatally.
     *
     *         <p>The last clause is the one that closes the hole. A source that died mid-replay
     *         disconnects, which releases the barrier and lets the engine's own path finish normally —
     *         so without consulting the gateways, a half-finished replay reports as completed.
     */
    public boolean completed() {
        return terminalStatus == GatewayStatus.COMPLETE && error == null && !interrupted
               && fatalFailures().isEmpty();
    }

    /**
     * A short, stable label for the manifest's {@code runStatus}: {@code "completed"}, {@code "failed"}
     * (an error ended it), {@code "aborted"} (interrupted), else {@code "unknown"}.
     *
     * @return the run-status label
     */
    public String runStatus() {
        if (completed())              return "completed";
        if (!fatalFailures().isEmpty()) return "failed";
        if (error != null)            return "failed";
        if (interrupted)              return "aborted";
        return "unknown";
    }

    /** Why the run ended, naming the failing gateways when that is the reason. */
    public String describe() {
        String status = runStatus();
        List<GatewayFailure> fatal = fatalFailures();
        if (!fatal.isEmpty()) {
            return status + ": " + fatal.stream().map(GatewayFailure::describe)
                    .collect(java.util.stream.Collectors.joining("; "));
        }
        if (error != null) return status + ": " + error;
        return status;
    }
}
