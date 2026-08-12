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
 * A read-only description of a run: its {@link OperatingMode}, time window, and the
 * datum-type universe it ran against.
 * Returned by {@link AbstractEngine#runInfo()} for <em>observers</em> — the code
 * that orchestrates a run and wants to record or report what it did: run manifests,
 * telemetry, audit records ("this run was {@code COMPRESSED_TIME} over window
 * {@code [start, end]}"), and tests that assert mode selection.
 *
 * <p><b>This is deliberately an engine-level, informational surface, not a control
 * one, and it is deliberately not reachable by actors.</b> An {@code Actor} (a
 * strategy) receives only a {@link Pub} to publish through — never the engine — so
 * it cannot obtain a {@code RunInfo} and cannot branch its logic on whether the run
 * is live or a replay. That is the point: see {@link AbstractGateway#operatingMode()}
 * for the parity rationale. The run orchestrator (which holds the engine) logs the
 * {@code RunInfo}; the components running inside the engine stay blind to it.
 *
 * <p>{@link #mode()} on a run that has been initialised is only ever
 * {@link OperatingMode#REAL_TIME} or {@link OperatingMode#COMPRESSED_TIME}
 * ({@link OperatingMode#UNDEFINED} before initialisation; {@link OperatingMode#MIXED}
 * is reserved and never selected — see {@code AbstractGateway.initialize()}).
 *
 * <p>{@link #typeFingerprint()} and {@link #providerIds()} record which datum-type
 * universe the run used (the composite registry fingerprint and the contributing
 * providers), so a run is traceable to the exact set of types it could route. The
 * fingerprint is {@code null} if any provider is unverifiable (has no manifest).
 *
 * @param mode           the operating mode selected for the run
 * @param startTime      epoch millis of the run window start
 * @param endTime        epoch millis of the run window end
 * @param typeFingerprint the composite datum-type registry fingerprint, or {@code null}
 * @param providerIds    the contributing datum-type provider IDs, sorted
 */
public record RunInfo(OperatingMode mode, long startTime, long endTime,
                      String typeFingerprint, List<String> providerIds) {
}
