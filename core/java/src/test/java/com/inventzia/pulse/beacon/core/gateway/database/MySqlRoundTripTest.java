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

import com.inventzia.pulse.beacon.core.AbstractGateway;
import com.inventzia.pulse.beacon.core.Topic;
import com.inventzia.pulse.data.datum.Datum;
import com.inventzia.pulse.data.schemas.marketdata.CdfBar;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The MySQL dialect's SQL, executed — against H2 in MySQL compatibility mode.
 *
 * <p><b>What this does and does not prove.</b> It proves the generated DDL, inserts and keyset paging
 * parse and execute, and that values survive the round trip through the mapper and reader. It does
 * <em>not</em> prove MySQL behaves identically: that is the whole reason the design doc insists the
 * guarantees are only demonstrated against the first real production engine. Treat this as a fast
 * check that the statements are well-formed, not as the proof.
 */
class MySqlRoundTripTest {

    private static final long START = 1_000_000L;
    private static final long END   = 2_000_000L;

    private DataSource ds;
    private final SqlDialect dialect = new MySqlDialect();
    private SqlTableBinding binding;

    private static final QualifiedTableName BARS = QualifiedTableName.of("bars");
    private static final Topic<CdfBar> TOPIC = new Topic<>("bars", CdfBar.class);

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource d = new JdbcDataSource();
        // MySQL compatibility mode, so backticks and LIMIT are understood.
        d.setURL("jdbc:h2:mem:mysql_" + UUID.randomUUID().toString().replace("-", "")
                 + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        d.setUser("sa");
        ds = d;

        binding = SqlTableBinding.of(BARS, StorageMapping.of(CdfBar.class))
                .withOrderingColumns(List.of("timestamp", "symb", IngestionId.COLUMN));

        TableRegistry registry = new TableRegistry(ds);
        registry.ensureRegistryTable();
        registry.beginProvisioning(binding, "test");
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute(dialect.createTableStatement(binding));
        }
        registry.markReady(BARS);
    }

    @AfterEach
    void tearDown() throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP ALL OBJECTS");
        }
    }

    @Test
    void theGeneratedDdlExecutesAndTheTableValidatesForBothRoles() throws Exception {
        try (Connection c = ds.getConnection()) {
            assertThat(TableValidator.validateForSink(c, binding).problems()).isEmpty();
            assertThat(TableValidator.validateForSource(c, binding).problems()).isEmpty();
        }
    }

    @Test
    void barsWrittenByTheSinkAreReadBackExactlyByTheSource() throws Exception {
        List<CdfBar> written = new ArrayList<>();
        BigDecimal[] prices = {
                new BigDecimal("12345.678901234567"),
                new BigDecimal("0.000000000001"),
                new BigDecimal("99999999.999999999999"),
        };
        for (int i = 0; i < prices.length; i++) {
            written.add(new CdfBar("SYM", START + 100L * (i + 1),
                    prices[i], prices[i], prices[i], prices[i], prices[i], null,
                    Instant.ofEpochMilli(START + 100L * (i + 1)), 7L + i,
                    LocalDate.of(2026, 1, 2 + i), null, null, null));
        }

        // Sink
        SqlSinkGateway sink = new SqlSinkGateway("sink", ds, binding, dialect, "run-mysql",
                SinkFailurePolicy.ESSENTIAL, START, END, 100, 2, 50);
        sink.validate();
        Thread writer = new Thread(sink, "sink-writer");
        writer.start();
        for (CdfBar bar : written) {
            sink.onEvent(TOPIC, bar);
        }
        sink.requestStop();
        writer.join(15_000);

        assertThat(sink.counts().committed()).isEqualTo(written.size());
        assertThat(sink.counts().isFinal()).isTrue();
        assertThat(sink.terminalFailure()).isEmpty();

        // Source
        List<CdfBar> read = Collections.synchronizedList(new ArrayList<>());
        AbstractGateway collector = new AbstractGateway("collector", START, END) {
            @Override public void run() { }
            @Override public <Q extends Datum> void onEvent(Topic<Q> t, Q p) { read.add((CdfBar) p); }
            @Override public <Q extends Datum> void publish(Topic<Q> t, Q p) { }
        };
        SqlSourceGateway<CdfBar> source = new SqlSourceGateway<>(
                "source", ds, binding, dialect, TOPIC, List.of(), START, END, 2);
        source.validate();
        source.registerSubscriber(collector, TOPIC, List.of("SYM"));
        source.run();

        assertThat(source.terminalFailure()).isEmpty();
        assertThat(read).hasSize(written.size());
        assertThat(source.pagesRead()).as("it really paged, with LIMIT").isGreaterThan(1);

        for (int i = 0; i < written.size(); i++) {
            CdfBar w = written.get(i), r = read.get(i);
            assertThat(r.symb()).isEqualTo(w.symb());
            assertThat(r.timestamp()).isEqualTo(w.timestamp());
            assertThat(r.op()).as("decimal %s must survive exactly", w.op())
                    .isEqualByComparingTo(w.op());
            assertThat(r.datetime()).as("an exact UTC instant, not a zone-converted one")
                    .isEqualTo(w.datetime());
            assertThat(r.date()).isEqualTo(w.date());
            assertThat(r.count()).isEqualTo(w.count());
        }
    }

    @Test
    void theIngestionIdConstraintIsEnforced() throws Exception {
        // The retry argument in section 7 rests on this constraint actually existing in the table.
        SqlSinkGateway sink = new SqlSinkGateway("sink", ds, binding, dialect, "run-dup",
                SinkFailurePolicy.OBSERVATIONAL, START, END, 100, 10, 50);
        Thread writer = new Thread(sink, "sink-writer");
        writer.start();
        CdfBar bar = new CdfBar("SYM", START + 100, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ONE, null, Instant.ofEpochMilli(START + 100), null,
                LocalDate.of(2026, 1, 2), null, null, null);
        sink.onEvent(TOPIC, bar);
        sink.requestStop();
        writer.join(15_000);
        assertThat(sink.counts().committed()).isEqualTo(1);

        // Inserting the same ingestion id again must be refused by the database.
        try (Connection c = ds.getConnection();
             java.sql.PreparedStatement ps = c.prepareStatement(dialect.insertStatement(binding))) {
            new DatumRowBinder(binding.mapping()).bind(ps, bar, new IngestionId("run-dup", 0));
            org.assertj.core.api.Assertions.assertThatThrownBy(ps::executeUpdate)
                    .isInstanceOf(java.sql.SQLException.class);
        }
    }
}
