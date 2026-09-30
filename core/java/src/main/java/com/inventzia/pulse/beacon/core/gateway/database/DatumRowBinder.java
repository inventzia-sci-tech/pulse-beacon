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

import com.inventzia.pulse.data.datum.Datum;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Binds a datum's fields onto an insert, in the mapping's column order.
 *
 * <p><b>A value that does not fit is rejected, never truncated.</b> A decimal wider than the column's
 * declared precision is a {@link ValueOutOfRangeException}, not a silently rounded number: the whole
 * reason {@code DECIMAL} is distinguished from {@code DOUBLE} is that someone downstream is going to
 * add these up and expect the total to be right. Rounding here would produce a table that disagrees
 * with the run that wrote it, and nothing would ever say so.
 *
 * <p>Accessors are resolved once per type and cached — {@link #onEvent}-path reflection on every field
 * of every event is the kind of cost that only shows up under load.
 *
 * <p>See {@code docs/pulse-sql-gateway.md} §4.
 */
public final class DatumRowBinder {

    private final StorageMapping mapping;
    private final Map<String, Method> accessors = new HashMap<>();

    public DatumRowBinder(StorageMapping mapping) {
        this.mapping = mapping;
        for (RecordComponent rc : mapping.datumType().getRecordComponents()) {
            accessors.put(rc.getName(), rc.getAccessor());
        }
    }

    /** A value the column cannot hold exactly. */
    public static class ValueOutOfRangeException extends RuntimeException {
        public ValueOutOfRangeException(String message) { super(message); }
    }

    /**
     * Bind every column plus the ingestion id.
     *
     * @return the number of parameters bound
     * @throws ValueOutOfRangeException if a value cannot be stored without losing precision
     */
    public int bind(PreparedStatement ps, Datum datum, IngestionId ingestionId) throws SQLException {
        int i = 1;
        for (ColumnMapping column : mapping.columns()) {
            bindOne(ps, i++, column, valueOf(datum, column.fieldName()));
        }
        ps.setString(i, ingestionId.encoded());
        return i;
    }

    private Object valueOf(Datum datum, String fieldName) {
        Method accessor = accessors.get(fieldName);
        if (accessor == null) {
            throw new IllegalStateException("no accessor for '" + fieldName + "' on "
                    + mapping.datumType().getName());
        }
        try {
            Object v = accessor.invoke(datum);
            // An Optional field is empty rather than null; both mean SQL NULL.
            return v instanceof Optional<?> o ? o.orElse(null) : v;
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("cannot read '" + fieldName + "' from "
                    + mapping.datumType().getName(), e);
        }
    }

    private void bindOne(PreparedStatement ps, int index, ColumnMapping column, Object value)
            throws SQLException {
        if (value == null) {
            if (!column.nullable()) {
                // Catching it here names the field; letting the driver reject it names the column and
                // leaves the operator to work backwards to the schema.
                throw new ValueOutOfRangeException("field '" + column.fieldName()
                        + "' is required by " + mapping.typeId() + " but was null");
            }
            ps.setNull(index, sqlTypeOf(column));
            return;
        }
        switch (column.type()) {
            case TEXT          -> ps.setString(index, (String) value);
            case INT64         -> ps.setLong(index, ((Number) value).longValue());
            case INT32         -> ps.setInt(index, ((Number) value).intValue());
            case DECIMAL       -> ps.setBigDecimal(index, checkedDecimal(column, (BigDecimal) value));
            case DOUBLE        -> ps.setDouble(index, ((Number) value).doubleValue());
            case BOOLEAN       -> ps.setBoolean(index, (Boolean) value);
            case TIMESTAMP_UTC -> ps.setTimestamp(index,
                                        Timestamp.from(checkedInstant(column, (Instant) value)),
                                        utcCalendar());
            case DATE          -> ps.setDate(index, Date.valueOf((LocalDate) value));
        }
    }

    /**
     * A decimal that fits the column, or an exception naming what would have been lost.
     *
     * <p>Scale is adjusted only when it is exact — trailing zeros carry no value, so {@code 1.50} into
     * a scale-12 column is fine. Anything that would actually round is refused.
     */
    private BigDecimal checkedDecimal(ColumnMapping column, BigDecimal value) {
        BigDecimal scaled;
        try {
            scaled = value.setScale(column.scale(), java.math.RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new ValueOutOfRangeException("field '" + column.fieldName() + "' value " + value
                    + " needs more than " + column.scale() + " decimal places; storing it would round"
                    + " it, so it is refused rather than silently changed");
        }
        if (scaled.precision() - scaled.scale() > column.precision() - column.scale()) {
            throw new ValueOutOfRangeException("field '" + column.fieldName() + "' value " + value
                    + " exceeds DECIMAL(" + column.precision() + ", " + column.scale() + ")");
        }
        return scaled;
    }

    /**
     * An instant the column can hold exactly, or an exception naming what would have been lost.
     *
     * <p>A timestamp column holds milliseconds, matching the routing time and the {@code TIMESTAMP(3)}
     * the generated DDL produces. An {@link Instant} carries nanoseconds, and
     * {@code Timestamp.from(instant)} would quietly drop the extra digits — the same silent rounding
     * a decimal is protected from, and no more acceptable here.
     */
    private static Instant checkedInstant(ColumnMapping column, Instant value) {
        if (value.getNano() % NANOS_PER_MILLI != 0) {
            throw new ValueOutOfRangeException("field '" + column.fieldName() + "' value " + value
                    + " has sub-millisecond precision, which the column cannot hold; storing it would"
                    + " truncate it, so it is refused rather than silently changed");
        }
        return value;
    }

    private static final int NANOS_PER_MILLI = 1_000_000;

    /** Timestamps are written in UTC explicitly; the JVM's default zone must not reach the table. */
    private static java.util.Calendar utcCalendar() {
        return java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
    }

    private static int sqlTypeOf(ColumnMapping column) {
        return switch (column.type()) {
            case TEXT          -> Types.VARCHAR;
            case INT64         -> Types.BIGINT;
            case INT32         -> Types.INTEGER;
            case DECIMAL       -> Types.DECIMAL;
            case DOUBLE        -> Types.DOUBLE;
            case BOOLEAN       -> Types.BOOLEAN;
            case TIMESTAMP_UTC -> Types.TIMESTAMP;
            case DATE          -> Types.DATE;
        };
    }
}
