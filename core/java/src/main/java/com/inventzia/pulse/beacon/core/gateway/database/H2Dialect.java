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
 * H2, characterised.
 *
 * <p>Used by the test suite, and a real dialect rather than a test double: it has exact {@code DECIMAL},
 * catalogs and schemas, generated columns and snapshot isolation. That is why the suite runs against H2
 * and not SQLite — SQLite would accept the same DDL and then store a decimal as a float, so passing
 * against it would prove nothing about the guarantee the storage mapping exists to keep.
 *
 * <p>Passing here still does not stand in for the first real production engine (§ "Recommended
 * sequence"). It keeps CI fast and honest; it does not make H2 the proof.
 */
public class H2Dialect extends GenericSqlDialect {

    @Override
    public String name() { return "h2"; }

    /** H2 supports generated columns, so the derived timestamp cannot drift from the epoch millis. */
    @Override
    public boolean supportsDerivedTimestamp() { return true; }

    @Override
    public String derivedTimestampExpression(String quotedTimeColumn) {
        // Explicitly UTC: the point of the column is that other tools can read it, and a local-time
        // rendering of an epoch would be wrong in a way nobody notices until a DST boundary.
        return "TIMESTAMPADD(MILLISECOND, " + quotedTimeColumn
             + ", TIMESTAMP '1970-01-01 00:00:00')";
    }

    /** H2 has repeatable-read snapshots, and holding one for a replay is not pathological as it is
     *  on PostgreSQL, where a long snapshot blocks vacuum. */
    @Override
    public boolean supportsReplaySnapshot() { return true; }
}
