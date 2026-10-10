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

import com.inventzia.pulse.data.schemas.marketdata.CdfBar;
import com.inventzia.pulse.data.schemas.platform.HeartBeat;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The MySQL dialect's generated SQL and declared capabilities.
 *
 * <p>These assert the <em>shape</em> of what MySQL will be sent. They do not prove MySQL accepts it —
 * only a live server does that, which is exactly the point the design doc makes about embedded
 * databases. {@code MySqlRoundTripTest} goes as far as an embedded engine honestly can.
 */
class MySqlDialectTest {

    private final SqlDialect dialect = new MySqlDialect();

    private static SqlTableBinding beats() {
        return SqlTableBinding.of(QualifiedTableName.of("heartbeats"),
                StorageMapping.of(HeartBeat.class))
                .withOrderingColumns(List.of("beatTime", "beatKey", IngestionId.COLUMN));
    }

    @Test
    void identifiersAreQuotedWithBackticks() {
        // A double-quoted identifier is a string literal to MySQL unless ANSI_QUOTES is set, so the
        // generic quoting would produce statements that parse and mean something else entirely.
        assertThat(dialect.quote("order")).isEqualTo("`order`");
        assertThat(dialect.quote("a`b")).as("an embedded backtick is doubled, not passed through")
                .isEqualTo("`a``b`");
        assertThat(dialect.createTableStatement(beats()))
                .contains("`beatKey`")
                .doesNotContain("\"beatKey\"");
    }

    @Test
    void pagingUsesLimitBecauseMySqlHasNoFetchFirst() {
        String page = dialect.selectPageStatement(beats(), true);
        assertThat(page).contains("LIMIT ?").doesNotContain("FETCH FIRST");
        // The ordering and the row-value cursor comparison still have to be there.
        assertThat(page).contains("ORDER BY").contains(") > (");
    }

    @Test
    void aPayloadTimestampIsDatetimeNotTimestamp() {
        // MySQL's TIMESTAMP is converted to and from the session time zone on every read and write, so
        // the same row reads back differently for a client elsewhere - and it cannot represent anything
        // outside 1970-2038. Neither is acceptable for an exact UTC instant.
        String ddl = dialect.createTableStatement(
                SqlTableBinding.of(QualifiedTableName.of("bars"), StorageMapping.of(CdfBar.class)));
        assertThat(ddl).contains("`datetime` DATETIME(3)");
        assertThat(ddl).doesNotContain("`datetime` TIMESTAMP");
    }

    @Test
    void decimalsKeepTheirPrecisionAndScale() {
        String ddl = dialect.createTableStatement(
                SqlTableBinding.of(QualifiedTableName.of("bars"), StorageMapping.of(CdfBar.class)));
        assertThat(ddl).contains("DECIMAL(" + StorageMapping.DEFAULT_DECIMAL_PRECISION
                                 + ", " + StorageMapping.DEFAULT_DECIMAL_SCALE + ")");
        assertThat(ddl).as("a decimal must never be rendered as floating point")
                .doesNotContain("`op` DOUBLE");
    }

    @Test
    void theRoutingTimeIsBigint() {
        assertThat(dialect.createTableStatement(beats())).contains("`beatTime` BIGINT");
    }

    @Test
    void noDerivedTimestampIsOfferedBecauseMySqlCannotGenerateItDeterministically() {
        // FROM_UNIXTIME depends on the session time zone, so MySQL refuses it in a generated column. A
        // written column could drift from the epoch millis beside it, which is worse than none.
        assertThat(dialect.supportsDerivedTimestamp()).isFalse();
        assertThat(dialect.createTableStatement(beats()))
                .doesNotContain(dialect.derivedTimestampColumn());
    }

    @Test
    void theDdlNamesInnoDbExplicitly() {
        // default_storage_engine is a server setting, so relying on "the default is InnoDB" is relying
        // on a stock configuration. MyISAM accepts commit() and ignores it, which would make the
        // delivery accounting and the replay snapshot fictional while everything looked fine.
        assertThat(dialect.createTableStatement(beats())).endsWith("ENGINE=InnoDB");
    }

    @Test
    void aNonTransactionalStorageEngineIsReported() {
        // MyISAM accepts commit() and ignores it, so the delivery accounting and the replay snapshot
        // would both be fictional. Stubbed rather than created: a managed MySQL may refuse to create a
        // MyISAM table at all (Aiven does), so this branch cannot be provoked against every server.
        SqlDialect.StorageEngineCheck bad =
                dialect.storageEngineCheck(stubConnection("MyISAM", null), QualifiedTableName.of("t"));
        assertThat(bad.problem()).contains("MyISAM").contains("does not support transactions");
        assertThat(bad.couldNotVerify()).isNull();

        SqlDialect.StorageEngineCheck good =
                dialect.storageEngineCheck(stubConnection("InnoDB", null), QualifiedTableName.of("t"));
        assertThat(good.problem()).as("InnoDB is what we require").isNull();
        assertThat(good.couldNotVerify()).isNull();
    }

    @Test
    void anUndeterminableEngineIsReportedAsUnverifiedNotAsFine() {
        // The distinction that matters: "I could not find out" must not be returned as "it is fine".
        // A MySQL-compatible server need not expose information_schema.tables.engine at all.
        SqlDialect.StorageEngineCheck check =
                dialect.storageEngineCheck(connectionThatCannotAnswer(), QualifiedTableName.of("t"));
        assertThat(check.problem()).as("not a definite problem - we do not know").isNull();
        assertThat(check.couldNotVerify())
                .as("but it must say so, or an unverified engine looks like a verified one")
                .isNotNull().contains("could not read the storage engine");
    }

    @Test
    void aMissingInformationSchemaRowIsAlsoReportedRatherThanPassed() {
        // The case the catalog bug produced: looking in the wrong database finds no row. Returning
        // "no problem" there is exactly how a cross-database table's engine went unchecked.
        SqlDialect.StorageEngineCheck check =
                dialect.storageEngineCheck(connectionWithNoRows(), QualifiedTableName.of("t"));
        assertThat(check.problem()).isNull();
        assertThat(check.couldNotVerify()).contains("no information_schema row");
    }

    @Test
    void theDatabaseIsTakenFromTheCatalogBecauseThatIsWhereMySqlPutsIt() {
        // Measured against a real server: Connector/J returns the database from getCatalog(), leaves
        // getSchema() null, and getCatalogTerm() is "database". Consulting only schema() meant a
        // binding naming a database was ignored and the connection's default inspected instead.
        java.util.List<String> bound = new java.util.ArrayList<>();
        dialect.storageEngineCheck(stubConnection(null, bound),
                new QualifiedTableName("chosen_db", null, "t"));
        assertThat(bound).as("the catalog must be the database the query filters on")
                .containsExactly("chosen_db");

        bound.clear();
        dialect.storageEngineCheck(stubConnection(null, bound),
                new QualifiedTableName(null, "schema_db", "t"));
        assertThat(bound).as("a driver configured with databaseTerm=SCHEMA still works")
                .containsExactly("schema_db");
    }

    @Test
    void innoDbRepeatableReadIsUsableForAReplaySnapshot() {
        assertThat(dialect.supportsReplaySnapshot()).isTrue();
    }

    @Test
    void aDuplicateIsIdentifiedByVendorCodeNotBySqlstate() {
        // MySQL reports a great many integrity failures as SQLSTATE 23000, a not-null violation
        // included, so the SQLSTATE alone cannot identify a duplicate.
        assertThat(dialect.isUniqueViolation(new SQLException("dup", "23000", 1062))).isTrue();
        assertThat(dialect.isUniqueViolation(new SQLException("dup named", "23000", 1586))).isTrue();
        assertThat(dialect.isUniqueViolation(new SQLException("not null", "23000", 1048)))
                .as("a not-null violation shares the SQLSTATE and is not a duplicate").isFalse();
        assertThat(dialect.isUniqueViolation(new SQLException("fk", "23000", 1452))).isFalse();
    }

    @Test
    void theIngestionIdIsThePrimaryKey() {
        // A primary key rather than a secondary unique index: managed MySQL commonly runs with
        // sql_require_primary_key=ON and refuses a table without one (found on Aiven), and the ingestion
        // id genuinely is the row's identity here. The uniqueness the retry proof depends on is the same
        // either way - a primary key is unique by definition.
        String ddl = dialect.createTableStatement(beats());
        assertThat(ddl).contains("PRIMARY KEY (" + dialect.quote(IngestionId.COLUMN) + ")");
        assertThat(ddl).as("and not also a redundant unique index on the same column")
                .doesNotContain("UNIQUE (" + dialect.quote(IngestionId.COLUMN) + ")");
    }

    // ------------------------------------------------------------------
    // Stubs for the storage-engine check
    //
    // Stubbed rather than run against a server because the interesting cases cannot be provoked
    // everywhere: a managed MySQL may refuse to create a MyISAM table at all, and a MySQL-compatible
    // server may not expose information_schema.tables.engine. The real-server behaviour is covered by
    // MySqlIntegrationTest.
    // ------------------------------------------------------------------

    /**
     * A Connection whose engine query returns one row holding {@code engine}, or no rows when it is
     * {@code null}; records the database bound as parameter 1 into {@code into} when given one.
     */
    private static java.sql.Connection stubConnection(String engine, java.util.List<String> into) {
        java.sql.ResultSet rs = (java.sql.ResultSet) java.lang.reflect.Proxy.newProxyInstance(
                MySqlDialectTest.class.getClassLoader(), new Class<?>[] {java.sql.ResultSet.class},
                (p, m, a) -> switch (m.getName()) {
                    case "next"      -> engine != null;
                    case "getString" -> engine;
                    default          -> defaultFor(m);
                });
        java.sql.PreparedStatement ps = (java.sql.PreparedStatement) java.lang.reflect.Proxy
                .newProxyInstance(MySqlDialectTest.class.getClassLoader(),
                        new Class<?>[] {java.sql.PreparedStatement.class},
                        (p, m, a) -> {
                            if (into != null && "setString".equals(m.getName())
                                    && ((Integer) a[0]) == 1 && a[1] != null) {
                                into.add((String) a[1]);
                            }
                            return "executeQuery".equals(m.getName()) ? rs : defaultFor(m);
                        });
        return (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(
                MySqlDialectTest.class.getClassLoader(), new Class<?>[] {java.sql.Connection.class},
                (p, m, a) -> "prepareStatement".equals(m.getName()) ? ps : defaultFor(m));
    }

    /** A Connection whose engine query returns no rows, as a wrong-database lookup would. */
    private static java.sql.Connection connectionWithNoRows() {
        return stubConnection(null, null);
    }

    /** A Connection that cannot answer the engine query, as a non-MySQL server could not. */
    private static java.sql.Connection connectionThatCannotAnswer() {
        return (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(
                MySqlDialectTest.class.getClassLoader(), new Class<?>[] {java.sql.Connection.class},
                (p, m, a) -> {
                    if ("prepareStatement".equals(m.getName())) {
                        throw new SQLException("Column \"ENGINE\" not found", "42122");
                    }
                    return defaultFor(m);
                });
    }

    private static Object defaultFor(java.lang.reflect.Method m) {
        Class<?> r = m.getReturnType();
        if (!r.isPrimitive())   return null;
        if (r == boolean.class) return Boolean.FALSE;
        if (r == void.class)    return null;
        return 0;
    }
}
