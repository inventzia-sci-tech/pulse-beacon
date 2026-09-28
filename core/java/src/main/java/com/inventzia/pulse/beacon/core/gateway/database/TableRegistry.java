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

import com.inventzia.pulse.data.datum.DatumTypeRegistry;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The {@code pulse_tables} registry: which datum type each data table holds, and how far its
 * provisioning got.
 *
 * <p><b>Reading is unprivileged; provisioning is not.</b> {@link #requireReady(SqlTableBinding)} is the
 * ordinary startup path and does nothing but a {@code SELECT}, so a source runs with read-only
 * credentials against a pre-provisioned binding — a property designed for rather than discovered later.
 * {@link #beginProvisioning} / {@link #markReady} / {@link #assertBinding} write, and are a separate,
 * deliberate act by someone holding the rights for it.
 *
 * <p><b>Provisioning is a state machine because atomicity is not portable.</b> MySQL implicitly commits
 * {@code CREATE TABLE}, so the caller's rollback cannot undo it. Rather than promise a guarantee only
 * some dialects keep, the intermediate state is recorded: register as {@code provisioning}, create the
 * table, mark {@code ready}. Both crash shapes then become states an adapter can report and resolve.
 *
 * <p><b>A table with no registry row is refused</b> unless the caller explicitly asserts the binding via
 * {@link #assertBinding}, which records it. That is the migration path for pre-existing tables, and it
 * is deliberately an explicit act rather than an inference from a table that merely looks compatible.
 *
 * <p>See {@code docs/pulse-sql-gateway.md} §5.
 */
public final class TableRegistry {

    /** The registry table's name. */
    public static final String REGISTRY_TABLE = "pulse_tables";

    /**
     * Stored in place of a null catalog or schema.
     *
     * <p>The registry's identity is the qualified table, so those columns belong in the primary key —
     * and a nullable primary-key column is not portable, nor does {@code NULL = NULL} compare equal in
     * SQL, which would make every lookup for a default-schema table miss.
     */
    private static final String ABSENT = "";

    private final DataSource dataSource;

    /**
     * @param dataSource injected, so the host application keeps ownership of pooling and credentials;
     *                   this class opens and closes its own connections and never holds one
     */
    public TableRegistry(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    // ------------------------------------------------------------------
    // Unprivileged: the ordinary startup path
    // ------------------------------------------------------------------

    /**
     * The registration for a table, if it has one.
     *
     * @throws SQLException if the registry cannot be read (including: it does not exist)
     */
    public Optional<TableRegistration> lookup(QualifiedTableName table) throws SQLException {
        String sql = "SELECT catalog_name, schema_name, table_name, type_id, type_version,"
                   + " schema_fingerprint, storage_mapping_version, state, created_at, created_by"
                   + " FROM " + REGISTRY_TABLE
                   + " WHERE catalog_name = ? AND schema_name = ? AND table_name = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, orAbsent(table.catalog()));
            ps.setString(2, orAbsent(table.schema()));
            ps.setString(3, table.table());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        }
    }

    /**
     * Resolve a binding for ordinary use, or refuse with a reason.
     *
     * <p>Read-only. Refuses an unregistered table (use {@link #assertBinding} to adopt one), a table
     * still {@code provisioning}, and any registration whose type, version, schema fingerprint or
     * storage-mapping version disagrees with what this build would read or write.
     *
     * @throws BindingMismatchException if the table is unregistered, not ready, or describes something
     *                                 other than this binding
     */
    public TableRegistration requireReady(SqlTableBinding binding) throws SQLException {
        QualifiedTableName table = binding.table();
        TableRegistration row = lookup(table).orElseThrow(() -> new BindingMismatchException(table,
                "\n  - it has no row in " + REGISTRY_TABLE + "; a table is not adopted just because it"
                + " looks compatible. Assert the binding explicitly to record it."));

        if (row.state() != ProvisioningState.READY) {
            throw new BindingMismatchException(table,
                    "\n  - it is still '" + row.state().stored() + "': provisioning did not finish, so"
                    + " the table may not exist or may not match its row. Resolve the provisioning"
                    + " state before using it.");
        }
        String expected = fingerprintOf(binding.mapping().typeId());
        if (!row.matches(binding.mapping(), expected)) {
            throw new BindingMismatchException(table, row.describeMismatch(binding.mapping(), expected));
        }
        return row;
    }

    // ------------------------------------------------------------------
    // Privileged: provisioning
    // ------------------------------------------------------------------

    /**
     * Create the registry table if it is not already there.
     *
     * <p>Existence is checked through {@link DatabaseMetaData} rather than {@code CREATE TABLE IF NOT
     * EXISTS}, which Oracle and SQL Server do not accept. {@code created_at} is epoch millis for the
     * same reason the routing time is: it is the one representation every dialect stores identically.
     */
    public void ensureRegistryTable() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            if (tableExists(c, QualifiedTableName.of(REGISTRY_TABLE))) return;
            String ddl = "CREATE TABLE " + REGISTRY_TABLE + " ("
                       + " catalog_name VARCHAR(256) NOT NULL,"
                       + " schema_name VARCHAR(256) NOT NULL,"
                       + " table_name VARCHAR(256) NOT NULL,"
                       + " type_id VARCHAR(512) NOT NULL,"
                       + " type_version INTEGER NOT NULL,"
                       + " schema_fingerprint VARCHAR(64),"
                       + " storage_mapping_version INTEGER NOT NULL,"
                       + " state VARCHAR(32) NOT NULL,"
                       + " created_at BIGINT NOT NULL,"
                       + " created_by VARCHAR(256),"
                       + " PRIMARY KEY (catalog_name, schema_name, table_name))";
            try (Statement st = c.createStatement()) {
                st.executeUpdate(ddl);
            }
        }
    }

    /**
     * Record a table as {@code provisioning}: step one of the two-step act.
     *
     * <p>Deliberately before the table is created, so that a crash leaves a {@code provisioning} row
     * pointing at a table that may not exist — a state that can be found and resolved — rather than a
     * created table nobody recorded.
     *
     * @throws BindingMismatchException if the table already has a registration
     */
    public TableRegistration beginProvisioning(SqlTableBinding binding, String createdBy)
            throws SQLException {
        QualifiedTableName table = binding.table();
        if (lookup(table).isPresent()) {
            throw new BindingMismatchException(table,
                    "\n  - it is already registered; provisioning would overwrite that record");
        }
        TableRegistration row = new TableRegistration(table,
                binding.mapping().typeId(),
                binding.mapping().typeVersion(),
                fingerprintOf(binding.mapping().typeId()),
                StorageMapping.MAPPING_VERSION,
                ProvisioningState.PROVISIONING,
                Instant.now(),
                createdBy);
        insert(row);
        return row;
    }

    /**
     * Mark a provisioned table ready: step three, after the table itself exists.
     *
     * @throws BindingMismatchException if there is no row to promote
     */
    public void markReady(QualifiedTableName table) throws SQLException {
        String sql = "UPDATE " + REGISTRY_TABLE + " SET state = ?"
                   + " WHERE catalog_name = ? AND schema_name = ? AND table_name = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, ProvisioningState.READY.stored());
            ps.setString(2, orAbsent(table.catalog()));
            ps.setString(3, orAbsent(table.schema()));
            ps.setString(4, table.table());
            if (ps.executeUpdate() == 0) {
                throw new BindingMismatchException(table,
                        "\n  - there is no " + REGISTRY_TABLE + " row to mark ready");
            }
        }
    }

    /**
     * Adopt a table that already exists but was never registered, recording the binding as {@code ready}.
     *
     * <p>The migration path, and an explicit act on purpose. The alternative — inferring the binding
     * from a table whose columns happen to line up — is exactly how a table ends up read as a type it
     * does not hold.
     *
     * @throws BindingMismatchException if the table does not exist, or is already registered
     */
    public TableRegistration assertBinding(SqlTableBinding binding, String createdBy)
            throws SQLException {
        QualifiedTableName table = binding.table();
        try (Connection c = dataSource.getConnection()) {
            if (!tableExists(c, table)) {
                throw new BindingMismatchException(table,
                        "\n  - it does not exist, so there is nothing to adopt");
            }
        }
        if (lookup(table).isPresent()) {
            throw new BindingMismatchException(table,
                    "\n  - it is already registered; use requireReady rather than asserting again");
        }
        TableRegistration row = new TableRegistration(table,
                binding.mapping().typeId(),
                binding.mapping().typeVersion(),
                fingerprintOf(binding.mapping().typeId()),
                StorageMapping.MAPPING_VERSION,
                ProvisioningState.READY,
                Instant.now(),
                createdBy);
        insert(row);
        return row;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Whether a table exists, asked of the driver rather than guessed from a failed query.
     *
     * <p>Identifier case is the trap here: most engines fold unquoted names to one case (H2 and
     * PostgreSQL disagree on which), so the name is tried as given and then case-insensitively.
     */
    public boolean tableExists(Connection c, QualifiedTableName table) throws SQLException {
        DatabaseMetaData md = c.getMetaData();
        try (ResultSet rs = md.getTables(table.catalog(), table.schema(), null,
                new String[] {"TABLE"})) {
            while (rs.next()) {
                if (table.table().equalsIgnoreCase(rs.getString("TABLE_NAME"))) return true;
            }
        }
        return false;
    }

    /**
     * The per-type schema fingerprint from pulse-data's provider manifest, or {@code null} if the
     * provider has no manifest.
     *
     * <p>{@code null} is reported as "unverifiable" rather than treated as a match: a provider that
     * cannot state its schema cannot have that schema checked, and pretending otherwise would defeat
     * the one check that catches <em>same type, changed schema</em>.
     */
    public static String fingerprintOf(String typeId) {
        for (DatumTypeRegistry.ProviderInfo p : DatumTypeRegistry.defaultRegistry().providers()) {
            for (DatumTypeRegistry.TypeEntry e : p.entries()) {
                if (e.typeId().equals(typeId)) return e.fingerprint();
            }
        }
        return null;
    }

    private void insert(TableRegistration row) throws SQLException {
        String sql = "INSERT INTO " + REGISTRY_TABLE
                   + " (catalog_name, schema_name, table_name, type_id, type_version,"
                   + "  schema_fingerprint, storage_mapping_version, state, created_at, created_by)"
                   + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, orAbsent(row.table().catalog()));
            ps.setString(2, orAbsent(row.table().schema()));
            ps.setString(3, row.table().table());
            ps.setString(4, row.typeId());
            ps.setInt(5, row.typeVersion());
            ps.setString(6, row.schemaFingerprint());
            ps.setInt(7, row.storageMappingVersion());
            ps.setString(8, row.state().stored());
            ps.setLong(9, row.createdAt().toEpochMilli());
            ps.setString(10, row.createdBy());
            ps.executeUpdate();
        }
    }

    private static TableRegistration read(ResultSet rs) throws SQLException {
        return new TableRegistration(
                new QualifiedTableName(orNull(rs.getString("catalog_name")),
                        orNull(rs.getString("schema_name")), rs.getString("table_name")),
                rs.getString("type_id"),
                rs.getInt("type_version"),
                rs.getString("schema_fingerprint"),
                rs.getInt("storage_mapping_version"),
                ProvisioningState.parse(rs.getString("state")),
                Instant.ofEpochMilli(rs.getLong("created_at")),
                rs.getString("created_by"));
    }

    private static String orAbsent(String s) { return s == null ? ABSENT : s; }

    private static String orNull(String s)   { return s == null || s.isEmpty() ? null : s; }
}
