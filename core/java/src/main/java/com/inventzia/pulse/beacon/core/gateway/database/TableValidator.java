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

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.JDBCType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Checks a real table against the binding that claims to describe it — differently for a source than
 * for a sink, because they are not the same question.
 *
 * <p><b>A source</b> needs every datum field present and readable with a compatible type. Extra columns
 * are simply irrelevant to it: it names the columns it selects.
 *
 * <p><b>A sink</b> needs all of that <em>and</em> needs every extra column to be nullable, defaulted or
 * generated. An extra {@code NOT NULL} column with no default is harmless to read and makes <em>every
 * insert fail</em> — so it must be caught at startup, not on the first write, when the run is already
 * live and the failure looks like a data problem rather than a configuration one.
 *
 * <p>Conflating the two checks would be a bug in either direction: a source refused for a column it
 * never touches, or a sink admitted that cannot insert a single row. See
 * {@code docs/pulse-sql-gateway.md} §5.
 */
public final class TableValidator {

    private TableValidator() {}

    /** One column as the database actually describes it. */
    public record ActualColumn(String name, JDBCType jdbcType, String typeName,
                               int size, int decimalDigits,
                               boolean nullable, boolean hasDefault, boolean generated) {

        /** Whether a row can be inserted without ever mentioning this column. */
        public boolean isOmittableOnInsert() {
            return nullable || hasDefault || generated;
        }
    }

    /** What a validation found. Empty {@link #problems()} means the table is usable. */
    public record Result(QualifiedTableName table, String role, List<String> problems,
                         List<String> warnings) {

        public boolean isValid() { return problems.isEmpty(); }

        /** Throw if unusable, naming every problem at once. */
        public void orThrow() {
            if (!isValid()) {
                throw new BindingMismatchException(table,
                        "\n  - as a " + role + ":\n      - " + String.join("\n      - ", problems));
            }
        }
    }

    /**
     * Validate for reading. Extra columns are ignored — the source selects by name.
     */
    public static Result validateForSource(Connection c, SqlTableBinding binding) throws SQLException {
        Map<String, ActualColumn> actual = describe(c, binding.table());
        List<String> problems = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        checkDatumColumns(binding, actual, problems);

        if (!binding.isReplayable()) {
            problems.add("no stable ordering tuple: ORDER BY the time column alone is not a total"
                    + " order, so rows sharing a timestamp could replay in a different order each run");
        }
        for (String ord : binding.orderingColumns()) {
            ActualColumn col = actual.get(key(ord));
            if (col == null) {
                problems.add("ordering column '" + ord + "' does not exist in the table");
                continue;
            }
            if (col.nullable()) {
                problems.add("ordering column '" + ord + "' is nullable; the tuple must be non-null"
                        + " for the order to be total");
            }
        }
        // Non-null is necessary and nowhere near sufficient. If the tuple can repeat, keyset paging
        // skips every row after the first at a duplicated value - silently, since the cursor does
        // advance and no page is ever re-read. Rows simply vanish from the replay with nothing
        // reporting it. So uniqueness must be VERIFIED against a declared constraint, never assumed.
        if (binding.isReplayable() && !uniquenessIsDeclared(c, binding)) {
            problems.add("the ordering tuple " + binding.orderingColumns() + " is not covered by any"
                    + " declared primary key or unique index, so rows sharing those values would be"
                    + " silently skipped. Add a unique constraint, or append a per-event unique column"
                    + " such as '" + IngestionId.COLUMN + "' to the tuple");
        }
        return new Result(binding.table(), "source", List.copyOf(problems), List.copyOf(warnings));
    }

    /**
     * Validate for writing: everything the source needs, plus every column the sink will not name must
     * be omittable.
     */
    public static Result validateForSink(Connection c, SqlTableBinding binding) throws SQLException {
        Map<String, ActualColumn> actual = describe(c, binding.table());
        List<String> problems = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        checkDatumColumns(binding, actual, problems);

        for (ActualColumn col : actual.values()) {
            boolean written = binding.mapping().columns().stream()
                    .anyMatch(m -> m.columnName().equalsIgnoreCase(col.name()))
                    || IngestionId.COLUMN.equalsIgnoreCase(col.name());
            if (!written && !col.isOmittableOnInsert()) {
                // The failure this check exists for: reads fine, every insert fails.
                problems.add("extra column '" + col.name() + "' is NOT NULL with no default and is not"
                        + " part of " + binding.mapping().typeId() + ", so every insert would fail");
            }
        }
        return new Result(binding.table(), "sink", List.copyOf(problems), List.copyOf(warnings));
    }

    /** Every datum field must be present, and a routing field must additionally be non-null. */
    private static void checkDatumColumns(SqlTableBinding binding, Map<String, ActualColumn> actual,
                                          List<String> problems) {
        for (ColumnMapping m : binding.mapping().columns()) {
            ActualColumn col = actual.get(key(m.columnName()));
            if (col == null) {
                problems.add("column '" + m.columnName() + "' is missing");
                continue;
            }
            if (!isCompatible(m, col)) {
                problems.add("column '" + m.columnName() + "' is " + col.typeName()
                        + " but " + m.fieldName() + " needs " + m.type()
                        + (m.type().needsPrecision()
                           ? " (" + m.precision() + "," + m.scale() + ")" : ""));
                continue;
            }
            // The right JDBC type is not the same as enough room in it. A DECIMAL(10,2) column is a
            // perfectly good DECIMAL and will still round a scale-12 value on the way in - which is
            // the very loss the DECIMAL/DOUBLE distinction exists to prevent, arriving by another
            // route. The driver reports precision and scale, so there is no excuse for not checking.
            String tooNarrow = tooNarrowFor(m, col);
            if (tooNarrow != null) {
                problems.add("column '" + m.columnName() + "' is " + describe(col)
                        + " but " + m.fieldName() + " needs " + tooNarrow
                        + "; values would be silently rounded");
            }
            if (m.role() != ColumnMapping.Role.VALUE && col.nullable()) {
                problems.add("routing column '" + m.columnName() + "' is nullable; the engine needs a"
                        + " key and a time on every event");
            }
        }
    }

    /**
     * Whether a column can carry a logical type without losing it.
     *
     * <p>Deliberately strict about {@code DECIMAL}: a decimal field in a {@code DOUBLE} column is the
     * exact loss the storage mapping exists to prevent, and it would pass any check that only asked
     * "is it numeric". The reverse — a double field in a decimal column — is allowed, since it loses
     * nothing.
     */
    private static boolean isCompatible(ColumnMapping m, ActualColumn col) {
        return switch (m.type()) {
            case TEXT      -> is(col, JDBCType.VARCHAR, JDBCType.CHAR, JDBCType.LONGVARCHAR,
                                 JDBCType.NVARCHAR, JDBCType.NCHAR, JDBCType.LONGNVARCHAR,
                                 JDBCType.CLOB, JDBCType.NCLOB);
            case INT64     -> is(col, JDBCType.BIGINT, JDBCType.NUMERIC, JDBCType.DECIMAL);
            case INT32     -> is(col, JDBCType.INTEGER, JDBCType.SMALLINT, JDBCType.BIGINT,
                                 JDBCType.NUMERIC, JDBCType.DECIMAL);
            case DECIMAL   -> is(col, JDBCType.DECIMAL, JDBCType.NUMERIC);
            case DOUBLE    -> is(col, JDBCType.DOUBLE, JDBCType.FLOAT, JDBCType.REAL,
                                 JDBCType.DECIMAL, JDBCType.NUMERIC);
            case BOOLEAN   -> is(col, JDBCType.BOOLEAN, JDBCType.BIT, JDBCType.TINYINT,
                                 JDBCType.SMALLINT);
            case TIMESTAMP_UTC -> is(col, JDBCType.TIMESTAMP, JDBCType.TIMESTAMP_WITH_TIMEZONE);
            case DATE      -> is(col, JDBCType.DATE);
        };
    }

    /**
     * Whether the column can represent everything the mapping can, or {@code null} if it can.
     *
     * <p>Checked as two independent capacities for a decimal: the digits after the point (scale) and
     * the digits before it (precision minus scale). A column can be wider overall and still lose the
     * fractional digits that matter.
     */
    private static String tooNarrowFor(ColumnMapping m, ActualColumn col) {
        switch (m.type()) {
            case DECIMAL -> {
                int neededScale    = m.scale();
                int neededIntegral = m.precision() - m.scale();
                int actualScale    = col.decimalDigits();
                int actualIntegral = col.size() - col.decimalDigits();
                if (actualScale < neededScale || actualIntegral < neededIntegral) {
                    return "DECIMAL(" + m.precision() + "," + m.scale() + ")";
                }
            }
            case INT64 -> {
                // Only meaningful when a 64-bit integer landed in a NUMERIC column; a real BIGINT
                // reports its own width and needs no check.
                if (col.jdbcType() == JDBCType.NUMERIC || col.jdbcType() == JDBCType.DECIMAL) {
                    if (col.decimalDigits() > 0 || col.size() < 19) return "19 integer digits";
                }
            }
            case INT32 -> {
                if (col.jdbcType() == JDBCType.NUMERIC || col.jdbcType() == JDBCType.DECIMAL) {
                    if (col.decimalDigits() > 0 || col.size() < 10) return "10 integer digits";
                }
            }
            case TIMESTAMP_UTC -> {
                // The routing time is epoch millis, and a payload timestamp is held to the same
                // resolution. A TIMESTAMP(0) column would drop the milliseconds without a word.
                if (col.decimalDigits() < MILLISECOND_DIGITS) {
                    return "TIMESTAMP(" + MILLISECOND_DIGITS + ")";
                }
            }
            default -> { /* TEXT length is not declared by the schema; nothing to compare against */ }
        }
        return null;
    }

    /** Fractional-second digits required of a timestamp column: milliseconds. */
    public static final int MILLISECOND_DIGITS = 3;

    private static String describe(ActualColumn col) {
        return switch (col.jdbcType()) {
            case DECIMAL, NUMERIC -> col.typeName() + "(" + col.size() + "," + col.decimalDigits() + ")";
            case TIMESTAMP, TIMESTAMP_WITH_TIMEZONE -> col.typeName() + "(" + col.decimalDigits() + ")";
            default -> col.typeName();
        };
    }

    private static boolean is(ActualColumn col, JDBCType... accepted) {
        for (JDBCType t : accepted) {
            if (col.jdbcType() == t) return true;
        }
        return false;
    }

    /**
     * Whether some declared primary key or unique index is a subset of the ordering tuple.
     *
     * <p>A subset is what matters, not an exact match: if a unique key's columns all appear in the
     * tuple, the tuple is unique too. Extra columns beyond it only refine the order further.
     *
     * <p>Asked of the catalog rather than of the data. A {@code SELECT} proving no duplicates exist
     * today says nothing about tomorrow, and on a large table it is not cheap enough to run at every
     * startup.
     */
    public static boolean uniquenessIsDeclared(Connection c, SqlTableBinding binding)
            throws SQLException {
        Set<String> tuple = binding.orderingColumns().stream()
                .map(TableValidator::key).collect(java.util.stream.Collectors.toSet());
        for (List<String> unique : declaredUniqueKeys(c, binding.table())) {
            if (!unique.isEmpty() && tuple.containsAll(unique)) {
                return true;
            }
        }
        return false;
    }

    /** Every declared unique column set: the primary key, plus each unique index. */
    private static List<List<String>> declaredUniqueKeys(Connection c, QualifiedTableName table)
            throws SQLException {
        DatabaseMetaData md = c.getMetaData();
        String name = resolveCase(c, table);
        List<List<String>> keys = new ArrayList<>();

        Map<Short, String> pk = new java.util.TreeMap<>();
        try (ResultSet rs = md.getPrimaryKeys(table.catalog(), table.schema(), name)) {
            while (rs.next()) {
                pk.put(rs.getShort("KEY_SEQ"), key(rs.getString("COLUMN_NAME")));
            }
        }
        if (!pk.isEmpty()) keys.add(List.copyOf(pk.values()));

        // unique=true, approximate=false: declared constraints only, not statistics.
        Map<String, List<String>> byIndex = new LinkedHashMap<>();
        try (ResultSet rs = md.getIndexInfo(table.catalog(), table.schema(), name, true, false)) {
            while (rs.next()) {
                String column = rs.getString("COLUMN_NAME");
                if (column == null) continue;                 // tableIndexStatistic row
                byIndex.computeIfAbsent(rs.getString("INDEX_NAME"), k -> new ArrayList<>())
                        .add(key(column));
            }
        }
        keys.addAll(byIndex.values());
        return keys;
    }

    /**
     * Discover a stable ordering tuple from the declared primary key, time column first.
     *
     * <p>The primary key is the default way to obtain a tuple, not the requirement: a declared unique
     * non-null column set is equally valid, and saying a table "cannot be replayed" without a primary
     * key overstates the restriction. This is the convenient case; a caller that knows better can
     * declare the tuple directly on the binding.
     *
     * @return the tuple, or empty if the table declares no primary key covering the time column
     */
    public static List<String> discoverOrderingColumns(Connection c, SqlTableBinding binding)
            throws SQLException {
        QualifiedTableName t = binding.table();
        Map<Short, String> pk = new java.util.TreeMap<>();
        DatabaseMetaData md = c.getMetaData();
        try (ResultSet rs = md.getPrimaryKeys(t.catalog(), t.schema(), resolveCase(c, t))) {
            while (rs.next()) {
                pk.put(rs.getShort("KEY_SEQ"), rs.getString("COLUMN_NAME"));
            }
        }
        if (pk.isEmpty()) return List.of();

        String timeColumn = binding.mapping().timeColumn().columnName();
        List<String> ordered = new ArrayList<>();
        ordered.add(timeColumn);
        for (String col : pk.values()) {
            if (!col.equalsIgnoreCase(timeColumn)) ordered.add(col);
        }
        // The tuple only makes the order total if the key itself does. A primary key that does not
        // include the time column still does, since the key alone is unique.
        return List.copyOf(ordered);
    }

    /** Every column of a table, keyed case-insensitively. */
    public static Map<String, ActualColumn> describe(Connection c, QualifiedTableName table)
            throws SQLException {
        Map<String, ActualColumn> columns = new LinkedHashMap<>();
        DatabaseMetaData md = c.getMetaData();
        try (ResultSet rs = md.getColumns(table.catalog(), table.schema(),
                resolveCase(c, table), null)) {
            while (rs.next()) {
                String name = rs.getString("COLUMN_NAME");
                JDBCType type;
                try {
                    type = JDBCType.valueOf(rs.getInt("DATA_TYPE"));
                } catch (IllegalArgumentException e) {
                    type = JDBCType.OTHER;      // a vendor type; reported as incompatible, not crashed on
                }
                columns.put(key(name), new ActualColumn(name, type,
                        rs.getString("TYPE_NAME"),
                        rs.getInt("COLUMN_SIZE"),
                        rs.getInt("DECIMAL_DIGITS"),
                        rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable,
                        rs.getString("COLUMN_DEF") != null,
                        "YES".equalsIgnoreCase(rs.getString("IS_GENERATEDCOLUMN"))));
            }
        }
        return columns;
    }

    /**
     * The table name in the case the catalog stores it.
     *
     * <p>{@code DatabaseMetaData} pattern arguments are case-sensitive, while unquoted identifiers are
     * folded — upward on H2 and Oracle, downward on PostgreSQL. Asking with the wrong case returns
     * nothing, which is indistinguishable from "no such table" and is a classic source of a validator
     * that passes on one engine and finds nothing on another.
     */
    private static String resolveCase(Connection c, QualifiedTableName table) throws SQLException {
        DatabaseMetaData md = c.getMetaData();
        try (ResultSet rs = md.getTables(table.catalog(), table.schema(), null,
                new String[] {"TABLE"})) {
            while (rs.next()) {
                String found = rs.getString("TABLE_NAME");
                if (table.table().equalsIgnoreCase(found)) return found;
            }
        }
        return table.table();
    }

    private static String key(String columnName) {
        return Objects.requireNonNull(columnName, "columnName").toLowerCase(Locale.ROOT);
    }
}
