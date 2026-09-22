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
 * A run-lifecycle observer: two notifications an orchestrator can attach to an engine to learn,
 * authoritatively and at the right moment, that a run has been initialised and that it has ended.
 *
 * <p><b>Why this exists.</b> Code that records a run (a run directory, a manifest, a recording,
 * a per-run log) needs two engine facts it must not compute for itself:
 * <ul>
 *   <li>the run's {@link RunInfo} — its {@link OperatingMode}, window, and datum-type universe —
 *       as decided by {@link AbstractEngine#initialize()}, delivered <em>before</em> dispatch begins
 *       so the recorder can be opened with the correct mode. Deriving the mode independently (e.g.
 *       from the window and the current wall clock) can disagree with the engine when the window
 *       crosses from current to past between the two decisions; this callback removes that race; and</li>
 *   <li>the terminal {@link RunOutcome} — whether the engine completed, failed, or was interrupted —
 *       so the manifest's engine outcome is the engine's own, not the launcher's guess.</li>
 * </ul>
 *
 * <p>The engine stays a pure event machine: it knows nothing about directories, manifests, retention,
 * or log appenders. It only announces these two lifecycle facts; every recording decision is made by
 * the listener, outside the engine. This is the sanctioned seam for run orchestration, alongside the
 * read-only {@link AbstractEngine#runInfo()} snapshot.
 *
 * <p>Both methods are called on the engine's own thread. Implementations must be quick and must not
 * throw back into the engine; the engine isolates and logs a listener failure and continues (a broken
 * observer never fails the run). {@link #onRunInitialized} fires exactly once after initialisation and
 * before dispatch; {@link #onRunTerminated} fires exactly once at the end, in every exit path
 * (completion, failure, interruption). If initialisation itself fails, {@link #onRunInitialized} does
 * not fire but {@link #onRunTerminated} still does, so a listener must tolerate termination without a
 * preceding initialisation.
 */
public interface RunListener {

    /**
     * The run has been initialised: its {@link OperatingMode} and datum-type universe are fixed and
     * no event has been dispatched yet. Called once, on the engine thread, before the dispatch loop.
     *
     * @param info the run's authoritative, read-only description
     */
    default void onRunInitialized(RunInfo info) {
        // no-op by default
    }

    /**
     * The run has ended. Called once, on the engine thread, in every exit path.
     *
     * @param outcome the terminal engine outcome (completed, failed, or interrupted)
     */
    default void onRunTerminated(RunOutcome outcome) {
        // no-op by default
    }
}
