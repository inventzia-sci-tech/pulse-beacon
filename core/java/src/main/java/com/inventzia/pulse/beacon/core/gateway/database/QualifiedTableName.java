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
 * A table's full identity: catalog, schema, name.
 *
 * <p>All three, because a bare table name is ambiguous — the same name routinely exists in several
 * schemas, and a registry keyed on the name alone would happily bind a run to the wrong one. Catalog
 * and schema are optional because not every engine has both, but when a dialect reports them they are
 * carried and compared.
 *
 * @param catalog the catalog, or {@code null} if the dialect has none
 * @param schema  the schema, or {@code null} if the dialect has none
 * @param table   the table name; required
 */
public record QualifiedTableName(String catalog, String schema, String table) {

    public QualifiedTableName {
        Objects.requireNonNull(table, "table");
        if (table.isBlank()) throw new IllegalArgumentException("table must not be blank");
        if (catalog != null && catalog.isBlank()) catalog = null;
        if (schema  != null && schema.isBlank())  schema  = null;
    }

    /** A table in the connection's default catalog and schema. */
    public static QualifiedTableName of(String table) {
        return new QualifiedTableName(null, null, table);
    }

    /** A table in a named schema of the default catalog. */
    public static QualifiedTableName of(String schema, String table) {
        return new QualifiedTableName(null, schema, table);
    }

    /**
     * The dotted form for messages and registry keys — <em>not</em> for interpolation into SQL.
     *
     * <p>Identifiers going into a statement must be quoted with the dialect's own quoting rules; this
     * is for humans and for equality.
     */
    public String qualified() {
        StringBuilder sb = new StringBuilder();
        if (catalog != null) sb.append(catalog).append('.');
        if (schema  != null) sb.append(schema).append('.');
        return sb.append(table).toString();
    }

    @Override
    public String toString() {
        return qualified();
    }
}
