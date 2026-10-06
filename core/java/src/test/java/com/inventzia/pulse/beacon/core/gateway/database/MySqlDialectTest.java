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
}
