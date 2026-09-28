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

import com.inventzia.pulse.data.schemas.platform.HeartBeat;
import com.inventzia.pulse.data.schemas.platform.TextMessage;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The table registry and its provisioning state machine, against a real database.
 *
 * <p>H2 rather than a mock, because the things that go wrong here are JDBC's: identifier case folding,
 * {@code NULL} never comparing equal, metadata that disagrees between engines. A mocked
 * {@code DatabaseMetaData} would assert my assumptions back at me.
 */
class TableRegistryTest {

    private String       dbName;
    private DataSource   ds;
    private TableRegistry registry;

    private static final QualifiedTableName BEATS = QualifiedTableName.of("heartbeats");

    @BeforeEach
    void setUp() throws Exception {
        dbName = "reg_" + UUID.randomUUID().toString().replace("-", "");
        ds = ownerDataSource();
        registry = new TableRegistry(ds);
        registry.ensureRegistryTable();
    }

    @AfterEach
    void tearDown() throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP ALL OBJECTS");
        }
    }

    /**
     * The owner's DataSource. DB_CLOSE_DELAY keeps the in-memory database alive between connections;
     * it is an admin-only setting, so only the owner's URL may carry it — see {@link #asUser}.
     */
    private DataSource ownerDataSource() {
        return h2("jdbc:h2:mem:" + dbName + ";DB_CLOSE_DELAY=-1", "sa", "");
    }

    /**
     * A non-admin user's DataSource. It joins the same in-memory database by name, without repeating
     * the admin-only setting the owner already applied.
     */
    private DataSource asUser(String user, String password) {
        return h2("jdbc:h2:mem:" + dbName, user, password);
    }

    private static DataSource h2(String url, String user, String password) {
        JdbcDataSource d = new JdbcDataSource();
        d.setURL(url);
        d.setUser(user);
        d.setPassword(password);
        return d;
    }

    private static SqlTableBinding binding(QualifiedTableName table,
                                           Class<? extends com.inventzia.pulse.data.datum.Datum> t) {
        return SqlTableBinding.of(table, StorageMapping.of(t));
    }

    // ------------------------------------------------------------------
    // Provisioning state machine
    // ------------------------------------------------------------------

    @Test
    void ensureRegistryTableIsIdempotent() throws Exception {
        registry.ensureRegistryTable();      // already created in setUp
        registry.ensureRegistryTable();
        assertThat(registry.lookup(BEATS)).isEmpty();
    }

    @Test
    void aTableIsNotUsableUntilProvisioningFinishes() throws Exception {
        SqlTableBinding b = binding(BEATS, HeartBeat.class);

        registry.beginProvisioning(b, "test");
        assertThat(registry.lookup(BEATS)).get()
                .extracting(TableRegistration::state).isEqualTo(ProvisioningState.PROVISIONING);

        // The half-provisioned state must be refused, not tolerated: the table may not even exist yet.
        assertThatThrownBy(() -> registry.requireReady(b))
                .isInstanceOf(BindingMismatchException.class)
                .hasMessageContaining("provisioning");

        createHeartbeatTable(BEATS.table());
        registry.markReady(BEATS);

        TableRegistration ready = registry.requireReady(b);
        assertThat(ready.state()).isEqualTo(ProvisioningState.READY);
        assertThat(ready.typeId()).isEqualTo(HeartBeat.TYPE_ID);
        assertThat(ready.storageMappingVersion()).isEqualTo(StorageMapping.MAPPING_VERSION);
    }

    @Test
    void aProvisioningRowWithNoTableIsAReportableState() throws Exception {
        // The crash-between-steps case. It must be findable and describable, not silent corruption.
        SqlTableBinding b = binding(BEATS, HeartBeat.class);
        registry.beginProvisioning(b, "test");

        Optional<TableRegistration> row = registry.lookup(BEATS);
        assertThat(row).isPresent();
        assertThat(row.get().state()).isEqualTo(ProvisioningState.PROVISIONING);
        try (Connection c = ds.getConnection()) {
            assertThat(registry.tableExists(c, BEATS))
                    .as("the table was never created; the row records exactly that").isFalse();
        }
    }

    @Test
    void markingReadyWithNoRowIsRefused() {
        assertThatThrownBy(() -> registry.markReady(BEATS))
                .isInstanceOf(BindingMismatchException.class)
                .hasMessageContaining("no " + TableRegistry.REGISTRY_TABLE + " row");
    }

    @Test
    void provisioningAnAlreadyRegisteredTableIsRefused() throws Exception {
        SqlTableBinding b = binding(BEATS, HeartBeat.class);
        registry.beginProvisioning(b, "test");
        assertThatThrownBy(() -> registry.beginProvisioning(b, "test"))
                .isInstanceOf(BindingMismatchException.class)
                .hasMessageContaining("already registered");
    }

    // ------------------------------------------------------------------
    // Refusing what it should refuse
    // ------------------------------------------------------------------

    @Test
    void anUnregisteredTableIsRefusedEvenWhenItLooksPerfect() throws Exception {
        // The table is exactly right. It is still refused, because "looks compatible" is not a binding.
        createHeartbeatTable(BEATS.table());
        assertThatThrownBy(() -> registry.requireReady(binding(BEATS, HeartBeat.class)))
                .isInstanceOf(BindingMismatchException.class)
                .hasMessageContaining("no row in " + TableRegistry.REGISTRY_TABLE);
    }

    @Test
    void sameTypeChangedSchemaIsCaught() throws Exception {
        // The failure a bare type identifier waves through, and the reason the fingerprint is stored.
        SqlTableBinding b = binding(BEATS, HeartBeat.class);
        createHeartbeatTable(BEATS.table());
        registry.beginProvisioning(b, "test");
        registry.markReady(BEATS);

        corruptFingerprint(BEATS, "0".repeat(64));

        assertThatThrownBy(() -> registry.requireReady(b))
                .isInstanceOf(BindingMismatchException.class)
                .hasMessageContaining("schema fingerprint differs")
                .hasMessageContaining("same type, changed schema");
    }

    @Test
    void aDifferentStorageMappingVersionIsCaught() throws Exception {
        // Same schema, different SQL representation of it — arrays as JSON today, a child table
        // tomorrow. A reader that ignored this would silently misread the older layout.
        SqlTableBinding b = binding(BEATS, HeartBeat.class);
        createHeartbeatTable(BEATS.table());
        registry.beginProvisioning(b, "test");
        registry.markReady(BEATS);

        execute("UPDATE " + TableRegistry.REGISTRY_TABLE
                + " SET storage_mapping_version = " + (StorageMapping.MAPPING_VERSION + 1)
                + " WHERE table_name = '" + BEATS.table() + "'");

        assertThatThrownBy(() -> registry.requireReady(b))
                .isInstanceOf(BindingMismatchException.class)
                .hasMessageContaining("storage mapping version");
    }

    @Test
    void aTableRegisteredForAnotherTypeIsRefused() throws Exception {
        createHeartbeatTable(BEATS.table());
        registry.beginProvisioning(binding(BEATS, HeartBeat.class), "test");
        registry.markReady(BEATS);

        assertThatThrownBy(() -> registry.requireReady(binding(BEATS, TextMessage.class)))
                .isInstanceOf(BindingMismatchException.class)
                .hasMessageContaining("registered for type");
    }

    @Test
    void everyMismatchIsReportedAtOnce() throws Exception {
        SqlTableBinding b = binding(BEATS, HeartBeat.class);
        createHeartbeatTable(BEATS.table());
        registry.beginProvisioning(b, "test");
        registry.markReady(BEATS);

        execute("UPDATE " + TableRegistry.REGISTRY_TABLE
                + " SET type_version = 99, storage_mapping_version = 98,"
                + " schema_fingerprint = '" + "f".repeat(64) + "'"
                + " WHERE table_name = '" + BEATS.table() + "'");

        assertThatThrownBy(() -> registry.requireReady(b))
                .isInstanceOf(BindingMismatchException.class)
                .hasMessageContaining("type version")
                .hasMessageContaining("fingerprint")
                .hasMessageContaining("storage mapping version");
    }

    // ------------------------------------------------------------------
    // Adopting an existing table
    // ------------------------------------------------------------------

    @Test
    void assertingABindingAdoptsAnExistingTable() throws Exception {
        createHeartbeatTable(BEATS.table());
        SqlTableBinding b = binding(BEATS, HeartBeat.class);

        TableRegistration row = registry.assertBinding(b, "migration");
        assertThat(row.state()).as("an asserted binding is ready, not provisioning")
                .isEqualTo(ProvisioningState.READY);
        assertThat(row.createdBy()).isEqualTo("migration");
        assertThat(registry.requireReady(b)).isNotNull();
    }

    @Test
    void assertingABindingForANonexistentTableIsRefused() {
        assertThatThrownBy(() -> registry.assertBinding(binding(BEATS, HeartBeat.class), "migration"))
                .isInstanceOf(BindingMismatchException.class)
                .hasMessageContaining("does not exist");
    }

    @Test
    void assertingTwiceIsRefused() throws Exception {
        createHeartbeatTable(BEATS.table());
        SqlTableBinding b = binding(BEATS, HeartBeat.class);
        registry.assertBinding(b, "migration");
        assertThatThrownBy(() -> registry.assertBinding(b, "migration"))
                .isInstanceOf(BindingMismatchException.class)
                .hasMessageContaining("already registered");
    }

    // ------------------------------------------------------------------
    // Identity and privilege
    // ------------------------------------------------------------------

    @Test
    void tablesOfTheSameNameInDifferentSchemasAreDistinctRegistrations() throws Exception {
        execute("CREATE SCHEMA alpha");
        execute("CREATE SCHEMA beta");
        QualifiedTableName a = QualifiedTableName.of("ALPHA", "heartbeats");
        QualifiedTableName b = QualifiedTableName.of("BETA", "heartbeats");
        createHeartbeatTable("alpha.heartbeats");
        createHeartbeatTable("beta.heartbeats");

        registry.assertBinding(binding(a, HeartBeat.class), "test");

        // The bare name is ambiguous; the registration must not leak across schemas.
        assertThat(registry.lookup(a)).isPresent();
        assertThat(registry.lookup(b)).as("a same-named table in another schema is not registered")
                .isEmpty();
    }

    @Test
    void aDefaultSchemaTableIsFoundDespiteNullNotComparingEqual() throws Exception {
        // Storing NULL for an absent schema would make every lookup miss, since NULL = NULL is unknown
        // in SQL. This is the regression guard for that.
        createHeartbeatTable(BEATS.table());
        registry.assertBinding(binding(BEATS, HeartBeat.class), "test");
        assertThat(registry.lookup(BEATS)).isPresent();
    }

    @Test
    void ordinaryStartupNeedsOnlyReadRights() throws Exception {
        // The claim in §1: a source runs against a pre-provisioned binding with read-only credentials.
        // Asserted against a real grant rather than by reading the code.
        createHeartbeatTable(BEATS.table());
        SqlTableBinding b = binding(BEATS, HeartBeat.class);
        registry.assertBinding(b, "test");

        execute("CREATE USER reader PASSWORD 'r'");
        execute("GRANT SELECT ON " + TableRegistry.REGISTRY_TABLE + " TO reader");
        execute("GRANT SELECT ON " + BEATS.table() + " TO reader");

        TableRegistry asReader = new TableRegistry(asUser("reader", "r"));
        assertThat(asReader.requireReady(b)).isNotNull();
        assertThat(asReader.lookup(BEATS)).isPresent();

        // ...and the privileged operations genuinely are privileged.
        assertThatThrownBy(() -> asReader.markReady(BEATS)).isInstanceOf(SQLException.class);
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private void createHeartbeatTable(String name) throws SQLException {
        execute("CREATE TABLE " + name + " ("
                + " beatKey VARCHAR(256) NOT NULL,"
                + " beatTime BIGINT NOT NULL,"
                + " PRIMARY KEY (beatKey, beatTime))");
    }

    private void corruptFingerprint(QualifiedTableName t, String fingerprint) throws SQLException {
        execute("UPDATE " + TableRegistry.REGISTRY_TABLE + " SET schema_fingerprint = '" + fingerprint
                + "' WHERE table_name = '" + t.table() + "'");
    }

    private void execute(String sql) throws SQLException {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }
}
