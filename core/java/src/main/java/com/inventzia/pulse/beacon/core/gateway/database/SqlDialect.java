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
     * Whether a unique violation can be told apart from other integrity errors.
     *
     * <p>The retry argument in §7 rests on this: a unique violation on the ingestion id <em>proves</em>
     * an earlier attempt committed. On an engine where that cannot be distinguished, an {@code unknown}
     * outcome stays unknown rather than being resolved on a guess.
     */
    default boolean distinguishesUniqueViolation() { return true; }

    /** The SQLSTATE class for a unique/primary-key violation ({@code 23505} in the standard). */
    default boolean isUniqueViolation(java.sql.SQLException e) {
        String state = e.getSQLState();
        return state != null && state.startsWith("23");
    }

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
        // The retry identity. Unique, because that is what turns a failed retry into proof.
        parts.add(quote(IngestionId.COLUMN) + " VARCHAR(320) NOT NULL");
        if (supportsDerivedTimestamp()) {
            parts.add(quote(derivedTimestampColumn()) + " TIMESTAMP(3) GENERATED ALWAYS AS ("
                      + derivedTimestampExpression(quote(mapping.timeColumn().columnName())) + ")");
        }
        parts.add("CONSTRAINT " + quote(uniqueConstraintName(binding))
                  + " UNIQUE (" + quote(IngestionId.COLUMN) + ')');
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

    /** The name of the derived timestamp column, where one exists. */
    default String derivedTimestampColumn() { return "_pulse_event_time_utc"; }

    /** The unique constraint name carrying the ingestion identity. */
    default String uniqueConstraintName(SqlTableBinding binding) {
        return "uq_" + binding.table().table() + "_pulse_ingestion";
    }
}
