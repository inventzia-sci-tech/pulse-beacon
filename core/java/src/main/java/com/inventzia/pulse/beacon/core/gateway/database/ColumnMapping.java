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

import java.util.Objects;

/**
 * One datum field's place in a table: its column, its logical type, and the role it plays.
 *
 * @param fieldName  the record component name, which is also the schema property and the wire name
 * @param columnName the column it maps to
 * @param type       the logical type that must be preserved
 * @param precision  total digits, for {@link SqlLogicalType#DECIMAL}; ignored otherwise
 * @param scale      digits after the point, for {@link SqlLogicalType#DECIMAL}; ignored otherwise
 * @param nullable   whether the schema makes this field optional
 * @param role       whether this field carries the routing key, the routing time, or neither
 */
public record ColumnMapping(String fieldName,
                            String columnName,
                            SqlLogicalType type,
                            int precision,
                            int scale,
                            boolean nullable,
                            Role role) {

    /** What a field is used for beyond carrying a value. */
    public enum Role {
        /** An ordinary payload field. */
        VALUE,
        /** The {@code x-datum-key} field: the routing key. */
        KEY,
        /**
         * The {@code x-datum-time} field: the routing time, stored as epoch milliseconds.
         * This is the column the source orders and pages on, so it is also the one that wants an
         * index (see {@code docs/pulse-sql-gateway.md} §2).
         */
        TIME
    }

    public ColumnMapping {
        Objects.requireNonNull(fieldName, "fieldName");
        Objects.requireNonNull(columnName, "columnName");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(role, "role");
        if (type.needsPrecision()) {
            if (precision <= 0)            throw new IllegalArgumentException(
                    "precision must be positive for " + type + " on field '" + fieldName + "'");
            if (scale < 0 || scale > precision) throw new IllegalArgumentException(
                    "scale must be between 0 and precision for field '" + fieldName + "'");
        }
        // The routing time is epoch millis by the Datum contract; a mapping that says otherwise is
        // a bug in the mapper, and it would silently break the ordering the whole replay rests on.
        if (role == Role.TIME && type != SqlLogicalType.INT64) {
            throw new IllegalArgumentException(
                    "the routing time must map to INT64 epoch millis, got " + type
                    + " on field '" + fieldName + "'");
        }
        if (role == Role.KEY && type != SqlLogicalType.TEXT) {
            throw new IllegalArgumentException(
                    "the routing key must map to TEXT, got " + type + " on field '" + fieldName + "'");
        }
        // A routing field is never optional: the engine needs both on every event.
        if (role != Role.VALUE && nullable) {
            throw new IllegalArgumentException(
                    "routing field '" + fieldName + "' cannot be nullable");
        }
    }

    /** A non-decimal column. */
    public static ColumnMapping of(String fieldName, String columnName, SqlLogicalType type,
                                   boolean nullable, Role role) {
        return new ColumnMapping(fieldName, columnName, type, 0, 0, nullable, role);
    }

    /** A decimal column, with the precision and scale that must be preserved. */
    public static ColumnMapping decimal(String fieldName, String columnName,
                                        int precision, int scale, boolean nullable) {
        return new ColumnMapping(fieldName, columnName, SqlLogicalType.DECIMAL,
                precision, scale, nullable, Role.VALUE);
    }
}
