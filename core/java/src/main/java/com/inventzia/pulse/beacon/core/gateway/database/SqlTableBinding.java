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
import java.util.Objects;

/**
 * Everything a gateway needs to know about a table, and nothing it needs to connect to one.
 *
 * <p><b>This does not own a connection, and that is the point.</b> A snapshot source holding a
 * repeatable-read transaction for the length of a replay and a sink committing in batches need
 * different transaction lifetimes; a shared connection would force one policy on both. So the binding
 * is immutable metadata plus conversion rules — freely shared between a source and a sink over the
 * same table — while each gateway manages its own connection and transaction against an
 * <em>injected</em> {@code DataSource}. The host application keeps ownership of pooling, credentials
 * and secrets, and a test can substitute one.
 *
 * <p>A consequence designed for rather than discovered: a source must work with <b>read-only
 * credentials</b> against a pre-provisioned binding. Constructing one performs no I/O and requires no
 * rights. Provisioning is a separate, privileged act — see {@link TableRegistry}.
 *
 * <p>See {@code docs/pulse-sql-gateway.md} §1.
 */
public final class SqlTableBinding {

    private final QualifiedTableName table;
    private final StorageMapping     mapping;
    private final List<String>       orderingColumns;

    private SqlTableBinding(QualifiedTableName table, StorageMapping mapping,
                            List<String> orderingColumns) {
        this.table           = table;
        this.mapping         = mapping;
        this.orderingColumns = List.copyOf(orderingColumns);
    }

    /**
     * A binding with no ordering tuple yet — sufficient for a sink, which appends and never needs a
     * replay order. A source additionally requires {@link #withOrderingColumns(List)}.
     */
    public static SqlTableBinding of(QualifiedTableName table, StorageMapping mapping) {
        return new SqlTableBinding(Objects.requireNonNull(table, "table"),
                Objects.requireNonNull(mapping, "mapping"), List.of());
    }

    /**
     * The same binding with a stable ordering tuple, which is what makes a replay reproducible.
     *
     * <p>The tuple must be unique and non-null across the table: {@code ORDER BY <time>} alone is not
     * a total order, and ties can come back in a different order between runs or plans. The TimeMachine
     * cannot repair that — it faithfully preserves whatever order the source gave it. The tuple always
     * begins with the routing time column, so paging and ordering agree.
     *
     * <p>Columns beyond the first need not belong to the datum. {@link IngestionId#COLUMN} is the
     * usual final tiebreak: it is unique per event by construction, where the datum's own fields may
     * legitimately repeat — two ticks for one instrument in the same millisecond are real data, not a
     * schema mistake. {@link TableValidator} confirms the tuple is actually unique before a replay.
     *
     * @throws IllegalArgumentException if the tuple is empty, does not start with the routing time
     *                                  column, or repeats a column
     */
    public SqlTableBinding withOrderingColumns(List<String> columns) {
        Objects.requireNonNull(columns, "columns");
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("an ordering tuple must not be empty");
        }
        String timeColumn = mapping.timeColumn().columnName();
        if (!columns.get(0).equals(timeColumn)) {
            throw new IllegalArgumentException(
                    "the ordering tuple must begin with the routing time column '" + timeColumn
                    + "', got '" + columns.get(0) + "'");
        }
        // Columns outside the datum are allowed after the first, and are often exactly what makes
        // the tuple unique: the ingestion id is a per-event identity, where the datum's own fields
        // may legitimately repeat. Two events can share a key and a millisecond; that is real market
        // data, not a schema mistake. The validator confirms such a column exists and is non-null.
        if (columns.stream().distinct().count() != columns.size()) {
            throw new IllegalArgumentException("the ordering tuple must not repeat a column: " + columns);
        }
        return new SqlTableBinding(table, mapping, columns);
    }

    public QualifiedTableName table() { return table; }

    public StorageMapping mapping() { return mapping; }

    /** The stable ordering tuple, or empty if none has been established (sink-only bindings). */
    public List<String> orderingColumns() { return orderingColumns; }

    /** Whether this binding can drive a reproducible replay. */
    public boolean isReplayable() { return !orderingColumns.isEmpty(); }

    /**
     * Ordering columns that are not fields of the datum — typically {@link IngestionId#COLUMN}.
     *
     * <p>They must still be selected, because the paging cursor reads its resume point from them.
     */
    public List<String> auxiliaryOrderingColumns() {
        return orderingColumns.stream()
                .filter(c -> mapping.columns().stream().noneMatch(m -> m.columnName().equals(c)))
                .toList();
    }

    @Override
    public String toString() {
        return "SqlTableBinding[" + table.qualified() + " -> " + mapping.typeId()
               + (isReplayable() ? ", order by " + orderingColumns : ", no ordering tuple") + "]";
    }
}
