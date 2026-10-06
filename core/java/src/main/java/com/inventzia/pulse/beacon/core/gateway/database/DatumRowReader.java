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

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Calendar;
import java.util.Optional;
import java.util.TimeZone;

/**
 * Rebuilds a datum from a row — the inverse of {@link DatumRowBinder}, and held to the same standard.
 *
 * <p>A decimal comes back as a {@link BigDecimal} and a timestamp is read in UTC with an explicit
 * calendar, because a round trip that does not return what it stored is not a round trip. The driver
 * would happily hand back a {@code double} for a numeric column on some engines; asking for the exact
 * type is what stops that.
 *
 * <p>A {@code null} in a column the schema declares required is a corrupt row, and is refused rather
 * than turned into a datum with a missing field that fails somewhere further downstream.
 */
public final class DatumRowReader<P extends Datum> {

    private final StorageMapping mapping;
    private final Constructor<P> canonical;

    @SuppressWarnings("unchecked")
    public DatumRowReader(StorageMapping mapping) {
        this.mapping = mapping;
        RecordComponent[] components = mapping.datumType().getRecordComponents();
        Class<?>[] types = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
        }
        try {
            Constructor<?> c = mapping.datumType().getDeclaredConstructor(types);
            c.setAccessible(true);
            this.canonical = (Constructor<P>) c;
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    "no canonical constructor on " + mapping.datumType().getName(), e);
        }
    }

    /** A row is not what the binding says it is. */
    public static class CorruptRowException extends RuntimeException {
        /** Serialised only if a caller chooses to; fixed so a future field cannot silently
         *  change the identity of an already-serialised instance. */
        private static final long serialVersionUID = 1L;

        public CorruptRowException(String message) { super(message); }
    }

    /** Read the current row as a datum. */
    public P read(ResultSet rs) throws SQLException {
        RecordComponent[] components = mapping.datumType().getRecordComponents();
        Object[] args = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            ColumnMapping column = mapping.columns().get(i);
            Object value = readColumn(rs, column);
            if (value == null && !column.nullable()) {
                throw new CorruptRowException("column '" + column.columnName() + "' is null but "
                        + mapping.typeId() + " requires " + column.fieldName());
            }
            args[i] = components[i].getType() == Optional.class
                    ? Optional.ofNullable(value)
                    : value;
        }
        try {
            return canonical.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new CorruptRowException("cannot build " + mapping.typeId() + " from the row: "
                    + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
        }
    }

    private Object readColumn(ResultSet rs, ColumnMapping column) throws SQLException {
        String name = column.columnName();
        Object value = switch (column.type()) {
            // The exact accessor per logical type: asking for the right one is what keeps a decimal
            // from coming back as a double on a driver that would rather give you one.
            case TEXT          -> rs.getString(name);
            case INT64         -> rs.getLong(name);
            case INT32         -> rs.getInt(name);
            case DECIMAL       -> rs.getBigDecimal(name);
            case DOUBLE        -> rs.getDouble(name);
            case BOOLEAN       -> rs.getBoolean(name);
            case TIMESTAMP_UTC -> toInstant(rs.getTimestamp(name, utc()));
            case DATE          -> toLocalDate(rs, name);
        };
        // getLong/getInt/getDouble/getBoolean return 0/false for SQL NULL, so the primitive accessors
        // need wasNull() to tell a real zero from a missing value.
        return rs.wasNull() ? null : value;
    }

    private static Instant toInstant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static LocalDate toLocalDate(ResultSet rs, String name) throws SQLException {
        java.sql.Date d = rs.getDate(name);
        return d == null ? null : d.toLocalDate();
    }

    private static Calendar utc() {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    }
}
