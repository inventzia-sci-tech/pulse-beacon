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

import java.util.List;

/**
 * The baseline dialect: standard SQL types, and no capability claimed beyond them.
 *
 * <p>What an engine gets before anyone has characterised it. Every optional capability is off, so an
 * uncharacterised engine is <em>slower and plainer</em> rather than quietly wrong: no derived
 * timestamp, no replay snapshot. That is the safe direction for a default to fail in.
 */
public class GenericSqlDialect implements SqlDialect {

    /**
     * Length for a text column, because some engines require one and the schema declares none.
     *
     * <p>255 and not more, for a reason found on a real server: under {@code utf8mb4} MySQL counts four
     * bytes per character toward its 3072-byte index limit, so a 512-character column costs 2KB in every
     * index that contains it. A composite ordering index over two such columns is then rejected outright.
     * 255 is the conventional safe width and leaves room for the ordering tuple.
     */
    protected static final int DEFAULT_TEXT_LENGTH = 255;

    @Override
    public String name() { return "generic"; }

    @Override
    public String columnType(ColumnMapping column) {
        return switch (column.type()) {
            case TEXT          -> "VARCHAR(" + DEFAULT_TEXT_LENGTH + ")";
            // The routing time, and the reason it is BIGINT: exact, dialect-independent, trivially
            // ordered, and the column the index and the keyset paging use.
            case INT64         -> "BIGINT";
            case INT32         -> "INTEGER";
            case DECIMAL       -> "DECIMAL(" + column.precision() + ", " + column.scale() + ")";
            case DOUBLE        -> "DOUBLE PRECISION";
            case BOOLEAN       -> "BOOLEAN";
            case TIMESTAMP_UTC -> "TIMESTAMP(3)";
            case DATE          -> "DATE";
        };
    }

    /** Engines this dialect is known to be correct for, for the run manifest. */
    public List<String> characterised() { return List.of(); }
}
