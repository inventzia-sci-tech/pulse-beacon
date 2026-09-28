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

import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * How one datum type is laid out as a table: the logical shape, before any dialect renders it.
 *
 * <p>Derived from the generated record — its components give the field names and types, and the
 * generated {@code DATUM_KEY_FIELD} / {@code DATUM_TIME_FIELD} constants say which two carry the
 * routing roles. That last part is why those constants exist: a record exposes {@code getDatumKey()}
 * as a <em>value</em>, and nothing else names the field it came from.
 *
 * <p><b>Version this.</b> {@link #MAPPING_VERSION} travels into the table registry beside the datum's
 * schema fingerprint, because the same schema can have more than one valid SQL representation
 * (arrays as JSON today, a child table tomorrow). Without it a later reader would silently misread
 * an earlier table. Bump it whenever the layout this class produces changes meaning.
 *
 * <p><b>What it refuses.</b> Anything it cannot map exactly, loudly and at startup:
 * <ul>
 *   <li><b>Collections and arrays</b> — {@code VectorValue.values} has no single defensible
 *       representation (native array, JSON, child table), and parallel arrays add a cross-field
 *       invariant on top. Rejected until that is decided rather than guessed at.</li>
 *   <li><b>Unknown types</b> — a field whose Java type has no exact SQL counterpart. Widening it to
 *       text and hoping is how a round trip stops being one.</li>
 * </ul>
 *
 * <p>See {@code docs/pulse-sql-gateway.md} §4.
 */
public final class StorageMapping {

    /**
     * The layout version, recorded alongside the datum's schema fingerprint.
     *
     * <p>1: scalar fields only; epoch-millis {@code BIGINT} routing time; column names identical to
     * field names.
     */
    public static final int MAPPING_VERSION = 1;

    /**
     * Precision and scale applied to a decimal field, because the schema does not declare them.
     *
     * <p>38 digits is the widest exact decimal every target dialect supports; 12 of them after the
     * point covers prices, rates and quantities without silently rounding. A value that does not fit
     * is <em>rejected</em> at write time rather than truncated — see {@code docs/pulse-sql-gateway.md}
     * §4. If the schema ever gains explicit precision, this default disappears in favour of it.
     */
    public static final int DEFAULT_DECIMAL_PRECISION = 38;
    public static final int DEFAULT_DECIMAL_SCALE     = 12;

    private final Class<? extends Datum> datumType;
    private final String typeId;
    private final int typeVersion;
    private final List<ColumnMapping> columns;
    private final ColumnMapping keyColumn;
    private final ColumnMapping timeColumn;

    private StorageMapping(Class<? extends Datum> datumType, String typeId, int typeVersion,
                           List<ColumnMapping> columns) {
        this.datumType = datumType;
        this.typeId = typeId;
        this.typeVersion = typeVersion;
        this.columns = List.copyOf(columns);
        this.keyColumn = columns.stream()
                .filter(c -> c.role() == ColumnMapping.Role.KEY).findFirst().orElseThrow();
        this.timeColumn = columns.stream()
                .filter(c -> c.role() == ColumnMapping.Role.TIME).findFirst().orElseThrow();
    }

    /**
     * Derive the mapping for a generated datum record.
     *
     * @param datumType a generated {@link Datum} record
     * @return the logical table shape
     * @throws UnsupportedMappingException if any field cannot be mapped exactly
     * @throws IllegalArgumentException    if the class is not a generated datum record
     */
    public static StorageMapping of(Class<? extends Datum> datumType) {
        if (!datumType.isRecord()) {
            throw new IllegalArgumentException(
                    datumType.getName() + " is not a record; only generated datum types can be mapped");
        }
        String typeId = constant(datumType, "TYPE_ID", String.class);
        int typeVersion = constant(datumType, "TYPE_VERSION", Integer.class);
        String keyField = constant(datumType, "DATUM_KEY_FIELD", String.class);
        String timeField = constant(datumType, "DATUM_TIME_FIELD", String.class);

        Map<String, ColumnMapping> byField = new LinkedHashMap<>();
        List<String> rejected = new ArrayList<>();

        for (RecordComponent rc : datumType.getRecordComponents()) {
            String name = rc.getName();
            ColumnMapping.Role role = name.equals(keyField)  ? ColumnMapping.Role.KEY
                                    : name.equals(timeField) ? ColumnMapping.Role.TIME
                                    : ColumnMapping.Role.VALUE;
            // Nullability comes from the schema's `required` list, which the generator records in
            // @JsonProperty(required = ...). Inferring it from the Java type instead would mark every
            // required BigDecimal nullable, since only primitives are non-null by construction.
            boolean nullable = !isRequired(rc);
            Class<?> raw = rc.getType() == Optional.class ? optionalPayload(rc) : rc.getType();

            if (Collection.class.isAssignableFrom(raw) || raw.isArray()) {
                rejected.add(name + " (" + raw.getSimpleName() + "): arrays are not mapped yet");
                continue;
            }
            SqlLogicalType type = logicalTypeOf(raw);
            if (type == null) {
                rejected.add(name + " (" + raw.getName() + "): no exact SQL representation");
                continue;
            }
            byField.put(name, type == SqlLogicalType.DECIMAL && role == ColumnMapping.Role.VALUE
                    ? ColumnMapping.decimal(name, name,
                            DEFAULT_DECIMAL_PRECISION, DEFAULT_DECIMAL_SCALE, nullable)
                    : ColumnMapping.of(name, name, type, nullable, role));
        }

        if (!rejected.isEmpty()) {
            throw new UnsupportedMappingException(typeId, rejected);
        }
        return new StorageMapping(datumType, typeId, typeVersion, new ArrayList<>(byField.values()));
    }

    /** The logical type for a Java type, or {@code null} if it has no exact counterpart. */
    private static SqlLogicalType logicalTypeOf(Class<?> raw) {
        if (raw == String.class)                        return SqlLogicalType.TEXT;
        if (raw == long.class    || raw == Long.class)  return SqlLogicalType.INT64;
        if (raw == int.class     || raw == Integer.class) return SqlLogicalType.INT32;
        if (raw == BigDecimal.class)                    return SqlLogicalType.DECIMAL;
        if (raw == double.class  || raw == Double.class) return SqlLogicalType.DOUBLE;
        if (raw == boolean.class || raw == Boolean.class) return SqlLogicalType.BOOLEAN;
        // Payload timestamps are data, not the routing contract: CdfBar carries both a `datetime`
        // and a `date` beside its epoch-millis routing time, and refusing them would reject the
        // main market-data type outright.
        if (raw == Instant.class)                       return SqlLogicalType.TIMESTAMP_UTC;
        if (raw == LocalDate.class)                     return SqlLogicalType.DATE;
        return null;
    }

    /** Whether the schema declares this field required, as recorded by the generator. */
    private static boolean isRequired(RecordComponent rc) {
        com.fasterxml.jackson.annotation.JsonProperty jp =
                rc.getAccessor().getAnnotation(com.fasterxml.jackson.annotation.JsonProperty.class);
        if (jp == null) {
            jp = rc.getAnnotation(com.fasterxml.jackson.annotation.JsonProperty.class);
        }
        return jp != null && jp.required();
    }

    private static Class<?> optionalPayload(RecordComponent rc) {
        java.lang.reflect.Type g = rc.getGenericType();
        if (g instanceof java.lang.reflect.ParameterizedType p
                && p.getActualTypeArguments().length == 1
                && p.getActualTypeArguments()[0] instanceof Class<?> c) {
            return c;
        }
        return Object.class;      // unresolvable payload: logicalTypeOf will reject it
    }

    @SuppressWarnings("unchecked")
    private static <T> T constant(Class<?> type, String name, Class<T> as) {
        try {
            Field f = type.getField(name);
            return (T) f.get(null);
        } catch (NoSuchFieldException e) {
            throw new IllegalArgumentException(
                    type.getName() + " has no " + name + "; regenerate it from pulse-data 0.4.0+"
                    + " (the generators emit the routing-field names a mapper needs)", e);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("cannot read " + name + " on " + type.getName(), e);
        }
    }

    public Class<? extends Datum> datumType() { return datumType; }

    public String typeId() { return typeId; }

    public int typeVersion() { return typeVersion; }

    /** Every column, in record-component order. */
    public List<ColumnMapping> columns() { return columns; }

    /** The column carrying the routing key. */
    public ColumnMapping keyColumn() { return keyColumn; }

    /** The column carrying the routing time: the ordering column of every source read. */
    public ColumnMapping timeColumn() { return timeColumn; }

    @Override
    public String toString() {
        return "StorageMapping[" + typeId + " v" + typeVersion
               + ", mapping v" + MAPPING_VERSION + ", " + columns.size() + " columns]";
    }
}
