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
 * How the sink writes a row that may already be there.
 *
 * <p>The choice is about <em>re-running</em>, not about retrying. A retry within one run is already safe:
 * every event carries an {@link IngestionId} derived from {@code (runId, sequence)} under a unique
 * constraint, so the same attempt cannot land twice. But that id is different on every run by
 * construction, so it does nothing for the quite different question of loading the same file twice.
 *
 * <p>See {@code docs/pulse-sql-gateway.md} §7 on why these are separate mechanisms.
 */
public enum WriteMode {

    /**
     * Append every event. Re-running the same input writes it again.
     *
     * <p>Correct when each run genuinely produces new events — a live recording, an append-only audit
     * trail — and the default, because it is the mode that cannot silently overwrite anything.
     */
    APPEND,

    /**
     * Converge on the natural key: business key plus event time.
     *
     * <p>Re-running the same input leaves the same number of rows, with the values refreshed. Right for
     * loading a file that may be re-delivered or corrected.
     *
     * <p><b>Not universally safe, which is why it is opt-in.</b> Where two genuinely distinct events can
     * share a key and an event time — two ticks for one instrument in the same millisecond — this would
     * keep only the last. Daily bars per symbol are the opposite case: the key and the day identify the
     * bar exactly, so convergence is what you want.
     *
     * <p>Requires the unique constraint on {@code (key, time)}; without it there is nothing to conflict
     * against and the upsert quietly degrades to an append.
     */
    UPSERT_ON_NATURAL_KEY;

    /** Whether this mode needs the natural-key unique constraint to exist. */
    public boolean needsNaturalKeyConstraint() {
        return this == UPSERT_ON_NATURAL_KEY;
    }
}
