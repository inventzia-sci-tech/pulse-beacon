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

import java.util.ArrayList;
import java.util.List;

/**
 * How one database engine spells what a {@link StorageMapping} requires, and what it can actually
 * promise.
 *
 * <p>The split between a logical type and a dialect is what keeps "the DDL was accepted" from being
 * mistaken for "the value round-trips". SQLite accepts {@code NUMERIC} and then stores a decimal as a
 * float; identical DDL on two engines proves nothing about identical behaviour. So a dialect does not
 * merely render text — it also declares the capabilities it genuinely has, and every capability
 * <b>defaults to off</b> for an engine nobody has characterised.
 *
 * <p>See {@code docs/pulse-sql-gateway.md} §4 and §6.
 */
public interface SqlDialect {

    /** A short name for messages and the run manifest. */
    String name();

    /**
     * The column type for a logical type, e.g. {@code DECIMAL(38,12)}.
     *
     * @throws UnsupportedMappingException if this engine cannot represent it exactly
     */
    String columnType(ColumnMapping column);

    /** Quote an identifier so a reserved word or an odd character cannot change the statement. */
    default String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    /** The quoted, qualified table name for use inside a statement. */
    default String qualify(QualifiedTableName table) {
        StringBuilder sb = new StringBuilder();
        if (table.catalog() != null) sb.append(quote(table.catalog())).append('.');
        if (table.schema()  != null) sb.append(quote(table.schema())).append('.');
        return sb.append(quote(table.table())).toString();
    }

    /**
     * Whether a column can carry a logical type without losing it.
     *
     * <p>On the dialect because the answer is engine-specific. The default reads the JDBC type code,
     * which is right wherever that code describes the declared type; an engine where it does not
     * overrides this.
     *
     * <p>Deliberately strict about {@code DECIMAL}: a decimal field in a floating-point column is the
     * exact loss the storage mapping exists to prevent, and it would pass any check that only asked
     * "is it numeric". The reverse — a double in a decimal column — is fine, since it loses nothing.
     */
    default boolean columnCanHold(ColumnMapping m, TableValidator.ActualColumn col) {
        return switch (m.type()) {
            case TEXT      -> isAnyOf(col, java.sql.JDBCType.VARCHAR, java.sql.JDBCType.CHAR,
                                java.sql.JDBCType.LONGVARCHAR, java.sql.JDBCType.NVARCHAR,
                                java.sql.JDBCType.NCHAR, java.sql.JDBCType.LONGNVARCHAR,
                                java.sql.JDBCType.CLOB, java.sql.JDBCType.NCLOB);
            case INT64     -> isAnyOf(col, java.sql.JDBCType.BIGINT, java.sql.JDBCType.NUMERIC,
                                java.sql.JDBCType.DECIMAL);
            case INT32     -> isAnyOf(col, java.sql.JDBCType.INTEGER, java.sql.JDBCType.SMALLINT,
                                java.sql.JDBCType.BIGINT, java.sql.JDBCType.NUMERIC,
                                java.sql.JDBCType.DECIMAL);
            case DECIMAL   -> isAnyOf(col, java.sql.JDBCType.DECIMAL, java.sql.JDBCType.NUMERIC);
            case DOUBLE    -> isAnyOf(col, java.sql.JDBCType.DOUBLE, java.sql.JDBCType.FLOAT,
                                java.sql.JDBCType.REAL, java.sql.JDBCType.DECIMAL,
                                java.sql.JDBCType.NUMERIC);
            case BOOLEAN   -> isAnyOf(col, java.sql.JDBCType.BOOLEAN, java.sql.JDBCType.BIT,
                                java.sql.JDBCType.TINYINT, java.sql.JDBCType.SMALLINT);
            case TIMESTAMP_UTC -> isAnyOf(col, java.sql.JDBCType.TIMESTAMP,
                                java.sql.JDBCType.TIMESTAMP_WITH_TIMEZONE);
            case DATE      -> isAnyOf(col, java.sql.JDBCType.DATE);
        };
    }

    /** Whether the column's JDBC type is one of these. */
    default boolean isAnyOf(TableValidator.ActualColumn col, java.sql.JDBCType... accepted) {
        for (java.sql.JDBCType t : accepted) {
            if (col.jdbcType() == t) return true;
        }
        return false;
    }

    /**
     * Whether this engine can derive a UTC timestamp column from the epoch-millis time column as a
     * <em>generated</em> column.
     *
     * <p>Generated and not written, so the two cannot disagree. An engine that would need it emulated
     * by an ordinary written column returns false: a convenience column that can drift is worse than
     * no convenience column. Defaults to false — see §6, this is explicitly not part of the contract.
     */
    default boolean supportsDerivedTimestamp() { return false; }

    /** The generated-column expression deriving a UTC timestamp from epoch millis. */
    default String derivedTimestampExpression(String timeColumn) {
        throw new UnsupportedOperationException(name() + " cannot derive a timestamp column");
    }

    /**
     * Whether a snapshot (repeatable-read) transaction held for the length of a replay is both
     * available and <em>acceptable</em> on this engine.
     *
     * <p>Two questions, deliberately answered as one: PostgreSQL has snapshot isolation, but a
     * long-running snapshot blocks vacuum and causes bloat, so "it exists" is not "use it for twenty
     * minutes". Defaults to false; the universal upper bound (§3) is what every dialect relies on.
     */
    default boolean supportsReplaySnapshot() { return false; }

    /**
     * Whether this error is specifically a unique or primary-key violation.
     *
     * <p><b>Exactly {@code 23505}, not the whole {@code 23} class.</b> SQLSTATE class 23 is "integrity
     * constraint violation" generally: {@code 23502} is a not-null violation and {@code 23503} a
     * foreign-key one. Accepting the class treats those as duplicates — which, in a retry, would turn
     * an insert that failed for an entirely unrelated reason into "proof" that an earlier attempt had
     * committed.
     *
     * <p>Even so, this is <em>not</em> what establishes delivery. A genuine {@code 23505} may be raised
     * by any unique constraint on the table — a natural-key index, say — and says nothing about the
     * ingestion identity. Outcomes are resolved by looking up the stored ingestion ids; this predicate
     * only distinguishes a benign duplicate from a real error while doing so.
     */
    default boolean isUniqueViolation(java.sql.SQLException e) {
        String state = e.getSQLState();
        return "23505".equals(state) || "23000".equals(state);   // 23000: some drivers' generic form
    }

    /**
     * Look up which of a set of ingestion ids are already stored.
     *
     * <p>This is what actually resolves an {@code unknown} outcome: a fact read back from the table,
     * rather than an inference drawn from the shape of an error.
     *
     * @param count how many ids are being asked about
     */
    default String selectStoredIngestionIdsStatement(SqlTableBinding binding, int count) {
        String placeholders = String.join(", ", java.util.Collections.nCopies(count, "?"));
        return "SELECT " + quote(IngestionId.COLUMN) + " FROM " + qualify(binding.table())
               + " WHERE " + quote(IngestionId.COLUMN) + " IN (" + placeholders + ")";
    }

    /** Largest {@code IN} list to send at once; Oracle caps at 1000, so stay well under. */
    default int maxInListSize() { return 200; }

    // ------------------------------------------------------------------
    // Generated statements
    // ------------------------------------------------------------------

    /**
     * {@code CREATE TABLE} for a binding: every datum column, the ingestion id under a unique
     * constraint, and — where the dialect can generate it — the derived timestamp of §6.
     */
    default String createTableStatement(SqlTableBinding binding) {
        StorageMapping mapping = binding.mapping();
        List<String> parts = new ArrayList<>();
        for (ColumnMapping c : mapping.columns()) {
            parts.add(quote(c.columnName()) + ' ' + columnType(c) + (c.nullable() ? "" : " NOT NULL"));
        }
        // The retry identity, and the row's identity: unique per event by construction, which is what
        // turns a failed retry into proof.
        parts.add(quote(IngestionId.COLUMN) + " VARCHAR(320) NOT NULL");
        if (supportsDerivedTimestamp()) {
            parts.add(quote(derivedTimestampColumn()) + " TIMESTAMP(3) GENERATED ALWAYS AS ("
                      + derivedTimestampExpression(quote(mapping.timeColumn().columnName())) + ")");
        }
        // PRIMARY KEY rather than a secondary UNIQUE index. Three reasons, in order of weight:
        // managed MySQL commonly runs with sql_require_primary_key=ON and refuses a table without one
        // (Aiven does, and that is where this was found); the ingestion id genuinely IS the row's
        // identity in this model, so a surrogate would be inventing a second one; and it saves an index,
        // since a primary key is already unique and that uniqueness is what the retry proof rests on.
        parts.add("CONSTRAINT " + quote(primaryKeyName(binding))
                  + " PRIMARY KEY (" + quote(IngestionId.COLUMN) + ')');
        return "CREATE TABLE " + qualify(binding.table()) + " (\n  "
               + String.join(",\n  ", parts) + "\n)";
    }

    /**
     * An index on the ordering tuple.
     *
     * <p>Its absence is a performance property, not a correctness one — every page would force a sort
     * of the window — so a missing index warns and does not refuse (§2).
     */
    default String createOrderingIndexStatement(SqlTableBinding binding) {
        if (!binding.isReplayable()) {
            throw new IllegalStateException("no ordering tuple to index");
        }
        List<String> quoted = binding.orderingColumns().stream().map(this::quote).toList();
        return "CREATE INDEX " + quote("ix_" + binding.table().table() + "_pulse_order")
               + " ON " + qualify(binding.table()) + " (" + String.join(", ", quoted) + ")";
    }

    /** The parameterised insert the sink issues, columns in mapping order then the ingestion id. */
    default String insertStatement(SqlTableBinding binding) {
        List<String> columns = new ArrayList<>();
        for (ColumnMapping c : binding.mapping().columns()) columns.add(quote(c.columnName()));
        columns.add(quote(IngestionId.COLUMN));
        String placeholders = String.join(", ", java.util.Collections.nCopies(columns.size(), "?"));
        return "INSERT INTO " + qualify(binding.table())
               + " (" + String.join(", ", columns) + ") VALUES (" + placeholders + ")";
    }

    /**
     * One page of a bounded, ordered read.
     *
     * <p>Keyset pagination, not {@code OFFSET}: offset degrades on large tables and, worse, shifts
     * rows under concurrent writes, so two runs of nominally the same replay would see different data.
     * The cursor is the ordering tuple's last value, and the tuple must be unique and non-null or the
     * comparison cannot resume exactly where the previous page stopped.
     *
     * <p>Every page carries the upper bound fixed at startup (§3). That is the universal half of the
     * consistency story — it needs no held transaction and no locks, and it works on every dialect.
     *
     * @param binding    the table and its ordering tuple
     * @param withCursor false for the first page, which has nothing to resume from
     */
    default String selectPageStatement(SqlTableBinding binding, boolean withCursor) {
        if (!binding.isReplayable()) {
            throw new IllegalStateException(
                    "a source needs an ordering tuple; " + binding.table().qualified() + " has none");
        }
        List<String> ordering = binding.orderingColumns();
        List<String> selected = new ArrayList<>(binding.mapping().columns().stream()
                .map(c -> quote(c.columnName())).toList());
        // The cursor resumes from the ordering tuple, so an ordering column outside the datum has to
        // come back in the row as well - otherwise the resume point cannot be read.
        binding.auxiliaryOrderingColumns().forEach(c -> selected.add(quote(c)));
        String columns = String.join(", ", selected);
        String time = quote(binding.mapping().timeColumn().columnName());
        String orderBy = String.join(", ", ordering.stream().map(this::quote).toList());

        StringBuilder sql = new StringBuilder("SELECT ").append(columns)
                .append(" FROM ").append(qualify(binding.table()))
                .append(" WHERE ").append(time).append(" >= ? AND ").append(time).append(" <= ?");
        if (withCursor) {
            // Row-value comparison: (time, ord...) > (lastTime, lastOrd...). Expressed as a tuple so
            // the resume point is exact even when the leading column repeats across the boundary.
            String tuple = String.join(", ", ordering.stream().map(this::quote).toList());
            String params = String.join(", ", java.util.Collections.nCopies(ordering.size(), "?"));
            sql.append(" AND (").append(tuple).append(") > (").append(params).append(')');
        }
        sql.append(" ORDER BY ").append(orderBy).append(' ').append(pageLimitClause());
        return sql.toString();
    }

    /** How this dialect spells "at most N rows". */
    default String pageLimitClause() {
        return "FETCH FIRST ? ROWS ONLY";
    }

    /**
     * The upper bound of the read: {@code max(timeColumn)} within the window, fixed once at startup.
     *
     * <p>Without it an appending feed would let later pages see rows earlier ones did not, and two runs
     * of the same replay would quietly differ. Nothing in the engine can detect that — each run is
     * internally consistent, they simply read different data.
     */
    default String selectUpperBoundStatement(SqlTableBinding binding) {
        String time = quote(binding.mapping().timeColumn().columnName());
        return "SELECT MAX(" + time + "), COUNT(*) FROM " + qualify(binding.table())
               + " WHERE " + time + " >= ? AND " + time + " <= ?";
    }

    /**
     * An insert that converges instead of duplicating: the <b>natural-key upsert</b> of §7.
     *
     * <p>Deliberately distinct from the ingestion identity. The ingestion id makes a <em>retry</em>
     * safe within one run; this makes <em>re-running</em> safe across runs. Loading the same file twice
     * must not double the data, and the business key plus the event time is what identifies "the same
     * bar" — whereas the ingestion id is different on every run by construction, so it cannot.
     *
     * <p>Opt-in, because it is not always correct: where two genuinely distinct events can share a key
     * and an event time, this would overwrite one with the other. See §7 on why the two mechanisms are
     * separate and both needed.
     *
     * <p>Requires the unique constraint from {@link #createNaturalKeyConstraintStatement}.
     */
    default String upsertStatement(SqlTableBinding binding) {
        List<String> columns = new ArrayList<>();
        for (ColumnMapping c : binding.mapping().columns()) columns.add(quote(c.columnName()));
        columns.add(quote(IngestionId.COLUMN));
        String placeholders = String.join(", ", java.util.Collections.nCopies(columns.size(), "?"));

        String key  = quote(binding.mapping().keyColumn().columnName());
        String time = quote(binding.mapping().timeColumn().columnName());
        // Everything except the natural key itself is refreshed; the key columns are what matched.
        List<String> updates = new ArrayList<>();
        for (ColumnMapping c : binding.mapping().columns()) {
            if (c.role() == ColumnMapping.Role.VALUE) {
                String col = quote(c.columnName());
                updates.add(col + " = excluded." + col);
            }
        }
        updates.add(quote(IngestionId.COLUMN) + " = excluded." + quote(IngestionId.COLUMN));

        return "INSERT INTO " + qualify(binding.table())
               + " (" + String.join(", ", columns) + ") VALUES (" + placeholders + ")"
               + " ON CONFLICT (" + key + ", " + time + ") DO UPDATE SET "
               + String.join(", ", updates);
    }

    /**
     * The unique constraint the natural-key upsert matches on: business key plus event time.
     *
     * <p>Without it the upsert has nothing to conflict against and simply inserts, so re-running would
     * duplicate exactly as before — silently, which is the worst form.
     */
    default String createNaturalKeyConstraintStatement(SqlTableBinding binding) {
        return "CREATE UNIQUE INDEX " + quote(naturalKeyConstraintName(binding))
               + " ON " + qualify(binding.table())
               + " (" + quote(binding.mapping().keyColumn().columnName())
               + ", " + quote(binding.mapping().timeColumn().columnName()) + ")";
    }

    /** The natural-key constraint's name. */
    default String naturalKeyConstraintName(SqlTableBinding binding) {
        return "uq_" + binding.table().table() + "_natural";
    }

    /** The name of the derived timestamp column, where one exists. */
    default String derivedTimestampColumn() { return "_pulse_event_time_utc"; }

    /** The primary key name carrying the ingestion identity. */
    default String primaryKeyName(SqlTableBinding binding) {
        return "pk_" + binding.table().table() + "_pulse_ingestion";
    }
}
