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
package com.inventzia.pulse.beacon.core.examples;

import com.inventzia.pulse.beacon.core.gateway.database.H2Dialect;
import com.inventzia.pulse.beacon.core.gateway.database.MySqlDialect;
import com.inventzia.pulse.beacon.core.gateway.database.QualifiedTableName;
import com.inventzia.pulse.beacon.core.gateway.database.SqlDialect;
import com.inventzia.pulse.beacon.core.gateway.database.SqlTableBinding;
import com.inventzia.pulse.beacon.core.gateway.database.StorageMapping;
import com.inventzia.pulse.beacon.core.gateway.database.TableRegistry;
import com.inventzia.pulse.beacon.core.gateway.database.IngestionId;
import com.inventzia.pulse.data.datum.Datum;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.List;

/**
 * Connection details for the SQL gateway examples.
 *
 * <p><b>Credentials live outside the repository, never in code or on a command line.</b> A password in a
 * source file reaches the repository; one on a command line is visible to every other process on the
 * machine and kept in shell history. So the examples read, in order of precedence:
 *
 * <ol>
 *   <li>the environment — {@code PULSE_DB_URL}, {@code PULSE_DB_USER}, {@code PULSE_DB_PASSWORD},
 *       {@code PULSE_DB_TABLE};</li>
 *   <li>a properties file — {@code $PULSE_DB_CONFIG}, or {@code ~/.pulse/db.properties} — with keys
 *       {@code db.url}, {@code db.user}, {@code db.password}, {@code db.table}.</li>
 * </ol>
 *
 * <p>A file outside the working tree is the safer default, and should be mode {@code 600}. Nothing here
 * logs a URL or a password, and the driver is reached through the JDBC service loader rather than by
 * naming a vendor class, so the driver stays an optional dependency.
 *
 * <p>One spelling trap worth knowing: the {@code mysql} command line writes {@code ssl-mode=REQUIRED},
 * while Connector/J wants {@code sslMode=REQUIRED}. A URL copied from one to the other silently loses
 * the setting.
 */
public final class SqlExampleDatabase {

    /** Default table for the {@code CdfBar} examples. */
    public static final String DEFAULT_TABLE = "pulse_cdf_bars";

    private final String url;
    private final String user;
    private final String password;
    private final String table;

    private SqlExampleDatabase(String url, String user, String password, String table) {
        this.url = url;
        this.user = user;
        this.password = password;
        this.table = table;
    }

    /**
     * Read the configuration, or fail with a message that says exactly what is missing.
     *
     * @throws IllegalStateException if the environment is not configured
     */
    public static SqlExampleDatabase fromEnvironment() {
        java.util.Properties file = loadConfigFile();
        String url   = firstOf(System.getenv("PULSE_DB_URL"),      file.getProperty("db.url"));
        String user  = firstOf(System.getenv("PULSE_DB_USER"),     file.getProperty("db.user"));
        String pass  = firstOf(System.getenv("PULSE_DB_PASSWORD"), file.getProperty("db.password"));
        String table = firstOf(System.getenv("PULSE_DB_TABLE"),    file.getProperty("db.table"));

        if (url == null || url.isBlank()) {
            throw new IllegalStateException("""
                    PULSE_DB_URL is not set. The SQL gateway examples need a database:

                      export PULSE_DB_URL='jdbc:mysql://<host>:3306/<database>'
                      export PULSE_DB_USER='<user>'
                      export PULSE_DB_PASSWORD='<password>'
                      export PULSE_DB_TABLE='pulse_cdf_bars'   # optional

                    ...or put them in ~/.pulse/db.properties (mode 600) as db.url, db.user,
                    db.password, db.table.

                    Either way, not as command-line arguments: a password there is visible to other
                    processes and kept in shell history.""");
        }
        return new SqlExampleDatabase(url, user, pass,
                table == null || table.isBlank() ? DEFAULT_TABLE : table);
    }

    /** The first non-blank value, or {@code null}. */
    private static String firstOf(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) return c;
        }
        return null;
    }

    /**
     * The properties file, or empty if there is none.
     *
     * <p>A missing file is not an error: the environment alone is a perfectly good way to configure this.
     * A file that exists but cannot be read is reported, because silently ignoring it would look exactly
     * like having configured nothing.
     */
    private static java.util.Properties loadConfigFile() {
        java.util.Properties props = new java.util.Properties();
        String explicit = System.getenv("PULSE_DB_CONFIG");
        java.nio.file.Path path = explicit != null && !explicit.isBlank()
                ? java.nio.file.Path.of(explicit)
                : java.nio.file.Path.of(System.getProperty("user.home"), ".pulse", "db.properties");
        if (!java.nio.file.Files.isReadable(path)) {
            return props;
        }
        try (var in = java.nio.file.Files.newInputStream(path)) {
            props.load(in);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot read " + path + ": " + e.getMessage(), e);
        }
        return props;
    }

    /**
     * A minimal {@link DataSource} over {@link DriverManager}.
     *
     * <p>Deliberately not a pool and deliberately not a vendor class: the gateways take an injected
     * DataSource precisely so the host application owns pooling and credentials, and an example should
     * not pretend to make that decision. The driver is found through the JDBC service loader.
     */
    public DataSource dataSource() {
        return new DataSource() {
            @Override public Connection getConnection() throws SQLException {
                return DriverManager.getConnection(url, user, password);
            }
            @Override public Connection getConnection(String u, String p) throws SQLException {
                return DriverManager.getConnection(url, u, p);
            }
            @Override public PrintWriter getLogWriter()                 { return null; }
            @Override public void setLogWriter(PrintWriter out)         { }
            @Override public void setLoginTimeout(int seconds)          { }
            @Override public int getLoginTimeout()                      { return 0; }
            @Override public java.util.logging.Logger getParentLogger() { return null; }
            @Override public <T> T unwrap(Class<T> iface) throws SQLException {
                throw new SQLFeatureNotSupportedException("unwrap");
            }
            @Override public boolean isWrapperFor(Class<?> iface)       { return false; }
        };
    }

    /**
     * The dialect for this URL.
     *
     * <p>Chosen from the JDBC scheme rather than configured separately, because getting it wrong is not a
     * cosmetic error: the dialect decides identifier quoting, the paging clause, and
     * how a duplicate is told apart from any other integrity failure.
     */
    public SqlDialect dialect() {
        String scheme = url.toLowerCase(java.util.Locale.ROOT);
        if (scheme.startsWith("jdbc:mysql") || scheme.startsWith("jdbc:mariadb")) {
            return new MySqlDialect();
        }
        if (scheme.startsWith("jdbc:h2")) {
            // H2 in MySQL compatibility mode is used to exercise the MySQL SQL shape in tests.
            return scheme.contains("mode=mysql") ? new MySqlDialect() : new H2Dialect();
        }
        throw new IllegalStateException("no dialect for this URL scheme; "
                + "supported: jdbc:mysql, jdbc:h2");
    }

    /** The dialect's name, for printing. */
    public String dialectName() {
        return dialect().name();
    }

    public QualifiedTableName table() {
        return QualifiedTableName.of(table);
    }

    /**
     * The binding for a datum type in this table, with an ordering tuple that is actually unique.
     *
     * <p>The ingestion id is the final column on purpose: the datum's own fields may legitimately
     * repeat — one instrument can produce several bars with the same timestamp — and a tuple that can
     * repeat makes keyset paging skip rows silently. See {@code docs/pulse-sql-gateway.md} §2.
     */
    public SqlTableBinding bindingFor(Class<? extends Datum> datumType, String... orderingFields) {
        StorageMapping mapping = StorageMapping.of(datumType);
        List<String> ordering = new java.util.ArrayList<>();
        ordering.add(mapping.timeColumn().columnName());
        for (String f : orderingFields) {
            if (!ordering.contains(f)) ordering.add(f);
        }
        ordering.add(IngestionId.COLUMN);
        return SqlTableBinding.of(table(), mapping).withOrderingColumns(ordering);
    }

    /**
     * Make sure the table exists and is registered, creating it if not.
     *
     * <p><b>A privileged act, and separate from running.</b> Ordinary startup needs only read rights
     * against a table someone has already provisioned (§1); this is the part that needs DDL rights, so
     * it is called out rather than hidden inside the gateway. Provisioning runs as a state machine —
     * register, create, mark ready — because MySQL commits {@code CREATE TABLE} implicitly and a
     * caller's rollback cannot undo it (§5).
     *
     * @return true if it created the table, false if it was already there
     */
    public boolean provisionIfAbsent(SqlTableBinding binding) throws SQLException {
        DataSource ds = dataSource();
        TableRegistry registry = new TableRegistry(ds);
        registry.ensureRegistryTable();

        // A previous run may have died mid-provisioning - a DDL the server refused, a lost connection.
        // That is a recoverable state rather than a corruption, so resolve it before deciding anything.
        registry.resolveProvisioning(binding).ifPresent(what -> System.out.println("  " + what));

        if (registry.lookup(binding.table()).isPresent()) {
            registry.requireReady(binding);     // refuses a table that is not what we expect
            return false;
        }
        System.out.println("provisioning " + binding.table().qualified()
                           + " for " + binding.mapping().typeId());
        registry.beginProvisioning(binding, "pulse-example");
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute(dialect().createTableStatement(binding));
            if (binding.isReplayable()) {
                // An index on the ordering tuple: a performance property, not a correctness one, so a
                // refusal must warn rather than fail provisioning (§2). Engines do refuse it - MySQL
                // caps an index key at 3072 bytes, and under utf8mb4 a few wide text columns exceed it.
                // The replay is then slower, not wrong.
                try {
                    st.execute(dialect().createOrderingIndexStatement(binding));
                } catch (SQLException e) {
                    System.out.println("  note: no ordering index (" + e.getMessage()
                            + "); the replay will work but will sort each page");
                }
            }
            // The natural key, so re-loading the same file converges instead of duplicating. Without
            // this constraint the upsert has nothing to conflict against and silently appends (§7).
            st.execute(dialect().createNaturalKeyConstraintStatement(binding));
        }
        registry.markReady(binding.table());
        return true;
    }

    /** Describe the target without revealing credentials. */
    public String describe() {
        String redacted = url.replaceAll("(?i)(password|pwd)=[^&;]*", "$1=***");
        int at = redacted.indexOf('@');
        return "table=" + table + " dialect=" + dialectName()
               + " url=" + (at >= 0 ? "***" + redacted.substring(at) : redacted);
    }
}
