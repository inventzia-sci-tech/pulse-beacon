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

import java.sql.SQLException;

/**
 * MySQL, characterised.
 *
 * <p>A real production engine, and the first one these gateways are meant to be proved against: an
 * embedded database can accept identical DDL and still behave differently, so only this kind of target
 * demonstrates the guarantees. Several things differ from standard SQL in ways that are not cosmetic.
 *
 * <ul>
 *   <li><b>Identifiers are quoted with backticks</b>, not double quotes — MySQL reads {@code "x"} as a
 *       string literal unless {@code ANSI_QUOTES} is set, so the generic quoting would produce
 *       statements that parse and mean something else.</li>
 *   <li><b>Paging is {@code LIMIT ?}</b>; MySQL has no {@code FETCH FIRST}.</li>
 *   <li><b>Payload timestamps are {@code DATETIME(3)}, not {@code TIMESTAMP}.</b> MySQL's
 *       {@code TIMESTAMP} converts to and from the session time zone on every read and write, and is
 *       limited to 1970–2038. Both are disqualifying for storing an exact UTC instant: the conversion
 *       makes the value depend on who is reading it. {@code DATETIME} stores what it is given.</li>
 *   <li><b>No derived timestamp column.</b> MySQL cannot use {@code FROM_UNIXTIME} in a generated
 *       column because it depends on the session time zone and so is not deterministic. A written
 *       column could drift from the epoch millis beside it, which is worse than having no convenience
 *       column at all — see {@code docs/pulse-sql-gateway.md} §6.</li>
 *   <li><b>{@code CREATE TABLE} commits implicitly.</b> This is the engine that motivates the
 *       provisioning state machine in §5: a caller's rollback cannot undo the table.</li>
 * </ul>
 */
public class MySqlDialect extends GenericSqlDialect {

    /** MySQL's duplicate-key error; SQLSTATE 23000 alone is far too broad here (see below). */
    private static final int ER_DUP_ENTRY            = 1062;
    private static final int ER_DUP_ENTRY_WITH_KEY_NAME = 1586;

    @Override
    public String name() { return "mysql"; }

    /**
     * Backticks. MySQL treats a double-quoted identifier as a string literal unless {@code ANSI_QUOTES}
     * is enabled, so the generic quoting would silently change what a statement means.
     */
    @Override
    public String quote(String identifier) {
        return '`' + identifier.replace("`", "``") + '`';
    }

    @Override
    public String pageLimitClause() { return "LIMIT ?"; }

    @Override
    public String columnType(ColumnMapping column) {
        return switch (column.type()) {
            case DOUBLE        -> "DOUBLE";
            // DATETIME and not TIMESTAMP: TIMESTAMP is converted to and from the session time zone, so
            // the same row would read back differently for a client in another zone, and it cannot
            // represent anything outside 1970-2038.
            case TIMESTAMP_UTC -> "DATETIME(3)";
            default            -> super.columnType(column);
        };
    }

    /**
     * MySQL cannot generate it deterministically, so it is not offered. See the class note.
     */
    /**
     * {@code ENGINE=InnoDB}, explicitly.
     *
     * <p>Not redundant: {@code default_storage_engine} is a server setting, so "the default is InnoDB"
     * is true of a stock server and not of every server. MyISAM accepts {@code commit()} and ignores
     * it, which would make the three-outcome accounting and the replay snapshot both fictional while
     * everything appeared to work. Naming the engine is the only way the DDL guarantees what the
     * dialect claims.
     */
    @Override
    public String createTableStatement(SqlTableBinding binding) {
        return super.createTableStatement(binding) + " ENGINE=InnoDB";
    }

    /**
     * Refuse a table whose storage engine cannot honour a transaction, and say so when it cannot be
     * determined rather than reporting silence as success.
     *
     * <p><b>The database is the catalog on MySQL, not the schema.</b> Connector/J maps a MySQL database
     * to the JDBC <em>catalog</em> — measured: {@code getCatalog()} returns the database,
     * {@code getSchema()} returns {@code null}, and {@code getCatalogTerm()} is literally
     * {@code "database"}. Consulting only {@code schema()} therefore looked in the connection's default
     * database for a table that might live in another, found no row, and reported no problem — so a
     * cross-database table's engine was never actually checked.
     */
    @Override
    public StorageEngineCheck storageEngineCheck(java.sql.Connection c, QualifiedTableName table) {
        // Catalog first: that is where Connector/J puts the database. Schema second, for a driver
        // configured with databaseTerm=SCHEMA. The connection's own database last.
        String database = table.catalog() != null ? table.catalog() : table.schema();
        String sql = "SELECT engine FROM information_schema.tables"
                   + " WHERE table_schema = COALESCE(?, DATABASE()) AND table_name = ?";
        try (java.sql.PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, database);
            ps.setString(2, table.table());
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    // The table's absence is reported by other checks; what matters here is that we
                    // did not establish the engine, so this must not read as approval.
                    return StorageEngineCheck.couldNotVerify("no information_schema row for "
                            + table.qualified() + " in "
                            + (database == null ? "the connection's current database" : database)
                            + ", so its storage engine was not verified");
                }
                String engine = rs.getString(1);
                if (engine == null) {
                    return StorageEngineCheck.couldNotVerify(
                            "information_schema reported no engine for " + table.qualified());
                }
                if ("InnoDB".equalsIgnoreCase(engine)) {
                    return StorageEngineCheck.ok();
                }
                return StorageEngineCheck.problem("its storage engine is " + engine
                        + ", which does not support transactions; the delivery accounting and the"
                        + " replay snapshot both require InnoDB");
            }
        } catch (java.sql.SQLException e) {
            // A MySQL-compatible server need not expose information_schema.tables.engine - H2's MySQL
            // mode has no such column - and the query can also fail for want of permission. Either way
            // the engine is unverified, which is reported rather than swallowed.
            return StorageEngineCheck.couldNotVerify("could not read the storage engine of "
                    + table.qualified() + ": " + e.getMessage());
        }
    }

    @Override
    public boolean supportsDerivedTimestamp() { return false; }

    /**
     * InnoDB's {@code REPEATABLE READ} is a true consistent read for the whole transaction, which is
     * exactly what a reproducible replay wants. Holding one for a long read grows the undo log rather
     * than blocking vacuum as on PostgreSQL, so it is acceptable for the length of a replay.
     */
    @Override
    public boolean supportsReplaySnapshot() { return true; }

    /**
     * MySQL reports a <em>great many</em> integrity failures as SQLSTATE 23000 — a not-null violation
     * included — so the SQLSTATE alone cannot identify a duplicate here. The vendor error code can.
     *
     * <p>This predicate no longer decides whether delivery happened (§7 resolves that by reading the
     * stored ingestion ids back), but it must still be right about what it claims.
     */
    /**
     * MySQL spells the upsert {@code ON DUPLICATE KEY UPDATE}, and has no {@code ON CONFLICT}.
     *
     * <p>It also matches on <em>any</em> unique key rather than a named one, which is why the natural-key
     * constraint has to be the only other unique key besides the ingestion id — and it is.
     */
    @Override
    public String upsertStatement(SqlTableBinding binding) {
        java.util.List<String> columns = new java.util.ArrayList<>();
        for (ColumnMapping c : binding.mapping().columns()) columns.add(quote(c.columnName()));
        columns.add(quote(IngestionId.COLUMN));
        String placeholders = String.join(", ", java.util.Collections.nCopies(columns.size(), "?"));

        java.util.List<String> updates = new java.util.ArrayList<>();
        for (ColumnMapping c : binding.mapping().columns()) {
            if (c.role() == ColumnMapping.Role.VALUE) {
                String col = quote(c.columnName());
                updates.add(col + " = VALUES(" + col + ")");
            }
        }
        String ing = quote(IngestionId.COLUMN);
        updates.add(ing + " = VALUES(" + ing + ")");

        return "INSERT INTO " + qualify(binding.table())
               + " (" + String.join(", ", columns) + ") VALUES (" + placeholders + ")"
               + " ON DUPLICATE KEY UPDATE " + String.join(", ", updates);
    }

    @Override
    public boolean isUniqueViolation(SQLException e) {
        return e.getErrorCode() == ER_DUP_ENTRY || e.getErrorCode() == ER_DUP_ENTRY_WITH_KEY_NAME;
    }
}
