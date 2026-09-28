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
 * What became of a batch the sink handed to the database.
 *
 * <p>Three outcomes, not two. A sink that only knows "written" and "lost" has to lie about the case
 * that actually happens in production: the database commits, and the connection dies before the
 * acknowledgement gets back. PostgreSQL documents that gap explicitly for connection loss and
 * failover, and no amount of client-side care closes it — the information is genuinely not available
 * to the client at that moment.
 *
 * <p>So {@link #UNKNOWN} is a first-class result and never folded into either neighbour. It is not a
 * permanent verdict: a retry carrying the same {@link IngestionId} resolves it, because a unique
 * violation on that column <em>proves</em> the earlier attempt committed. See
 * {@code docs/pulse-sql-gateway.md} §7.
 */
public enum DeliveryOutcome {

    /** The commit was acknowledged. The rows are in the database. */
    COMMITTED,

    /** The transaction demonstrably failed or rolled back. The rows are not in the database. */
    NOT_COMMITTED,

    /**
     * The outcome could not be established — the connection was lost at commit, or the server failed
     * over mid-transaction.
     *
     * <p>Retrying resolves it: the retry either inserts (it had not committed) or violates the
     * ingestion-id uniqueness (it had). What must never happen is guessing, in either direction.
     */
    UNKNOWN;

    /** Whether this outcome still leaves the rows' fate open. */
    public boolean isResolved() {
        return this != UNKNOWN;
    }
}
