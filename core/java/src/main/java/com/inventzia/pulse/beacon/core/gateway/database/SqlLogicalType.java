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
 * The logical SQL type a datum field maps to, independent of any dialect.
 *
 * <p>Deliberately small and deliberately not SQL text. A logical type says <em>what must be
 * preserved</em>; a dialect decides how to spell it and whether it can keep that promise. Splitting
 * them is what stops "the DDL was accepted" being mistaken for "the value round-trips" — SQLite will
 * accept {@code NUMERIC} and then store a decimal as a float, so identical DDL on two engines proves
 * nothing about identical behaviour.
 *
 * <p>Scalars only, for now. Arrays ({@code VectorValue.values}) and the cross-field invariants that
 * come with them ({@code x-parallel-to}) have no single defensible representation — native array,
 * JSON column, or child table each change the row-to-datum relationship — so a type containing one
 * is rejected with a clear message rather than guessed at. See {@code docs/pulse-sql-gateway.md} §4.
 */
public enum SqlLogicalType {

    /** Variable-length text. */
    TEXT,

    /** 64-bit signed integer. Also how every {@code x-datum-time} field is stored. */
    INT64,

    /** 32-bit signed integer. */
    INT32,

    /**
     * Exact decimal. The precision and scale are carried on the {@link ColumnMapping}, not here,
     * because the schema does not currently declare them and the default must be visible where it
     * is applied rather than buried in an enum.
     */
    DECIMAL,

    /**
     * Binary floating point. Distinct from {@link #DECIMAL} on purpose: a field declared as a
     * double is one whose author accepted floating-point semantics, and silently widening a decimal
     * into one would lose exactly what {@code DECIMAL} exists to keep.
     */
    DOUBLE,

    /** Boolean. */
    BOOLEAN,

    /**
     * An instant on the timeline, stored and read as UTC.
     *
     * <p>Distinct from the routing time, which is always epoch millis by the {@code Datum} contract.
     * This is an ordinary payload value ({@code CdfBar.datetime}), and conflating the two would
     * either reject real types or admit timezone ambiguity into the ordering key. The dialect
     * declares the precision it can keep; a value finer than that is rejected, not truncated.
     */
    TIMESTAMP_UTC,

    /** A calendar date with no time and no zone ({@code CdfBar.date}). */
    DATE;

    /** Whether this type needs a precision and scale to be fully specified. */
    public boolean needsPrecision() {
        return this == DECIMAL;
    }
}
