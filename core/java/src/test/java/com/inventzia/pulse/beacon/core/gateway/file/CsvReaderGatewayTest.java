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
package com.inventzia.pulse.beacon.core.gateway.file;

import com.inventzia.pulse.beacon.core.AbstractGateway;
import com.inventzia.pulse.beacon.core.GatewayStatus;
import com.inventzia.pulse.beacon.core.Topic;
import com.inventzia.pulse.beacon.core.examples.CdfBarCsvMapper;
import com.inventzia.pulse.data.datum.Datum;
import com.inventzia.pulse.data.schemas.marketdata.CdfBar;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading a CSV market-data file, and the interpretations that cannot be guessed.
 *
 * <p>The sharpest of those is the time zone: the file's timestamps are naive, the routing time is an
 * absolute instant, and the conversion between them moves every event by whole hours.
 */
class CsvReaderGatewayTest {

    private static final long START = 0L;
    private static final long END   = 4_102_444_800_000L;

    private static final Topic<CdfBar> BARS = new Topic<>("bars", CdfBar.class);

    private static final String HEADER = "date,datetime,op,hi,lo,cl,vwap,count,vlm,symb";
    private static final String ROW_1  = "1/4/2021,1/4/2021 23:00,39.64,40.99,39.18,39.49,39.79108891,12894,23204,CL";
    private static final String ROW_2  = "1/5/2021,1/5/2021 23:00,39.26,41.12,39.24,40.76,40.55781526,19191,34146,CL";

    private Path csv(String... lines) throws Exception {
        Path f = Files.createTempFile("bars", ".csv");
        f.toFile().deleteOnExit();
        Files.write(f, List.of(lines));
        return f;
    }

    /** Collects what the reader published. */
    private static final class Collector extends AbstractGateway {
        final List<CdfBar> seen = Collections.synchronizedList(new ArrayList<>());
        Collector() { super("collector", START, END); setDriveClock(false); }
        @Override public void run() { }
        @Override public <Q extends Datum> void onEvent(Topic<Q> t, Q p) { seen.add((CdfBar) p); }
        @Override public <Q extends Datum> void publish(Topic<Q> t, Q p) { }
    }

    private Collector read(Path file, ZoneId zone) {
        Collector collector = new Collector();
        CsvReaderGateway<CdfBar> reader = new CsvReaderGateway<>(
                "reader", BARS, List.of("CL"), file, new CdfBarCsvMapper(zone), START, END);
        reader.registerSubscriber(collector, BARS, List.of("CL"));
        reader.run();
        return collector;
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    @Test
    void readsEveryRowAndMapsItExactly() throws Exception {
        Collector c = read(csv(HEADER, ROW_1, ROW_2), ZoneId.of("UTC"));

        assertThat(c.seen).hasSize(2);
        CdfBar first = c.seen.get(0);
        assertThat(first.symb()).isEqualTo("CL");
        assertThat(first.op()).isEqualByComparingTo(new BigDecimal("39.64"));
        assertThat(first.cl()).isEqualByComparingTo(new BigDecimal("39.49"));
        assertThat(first.vwap()).as("eight decimal places, straight from the text")
                .isEqualByComparingTo(new BigDecimal("39.79108891"));
        assertThat(first.count()).isEqualTo(12894L);
        assertThat(first.vlm()).isEqualByComparingTo(new BigDecimal("23204"));
        assertThat(first.date()).isEqualTo(LocalDate.of(2021, 1, 4));
        assertThat(first.datetime()).isEqualTo(Instant.parse("2021-01-04T23:00:00Z"));
        assertThat(first.timestamp()).isEqualTo(first.datetime().toEpochMilli());
    }

    @Test
    void theTimeZoneMovesTheRoutingTime() throws Exception {
        // Not a formatting detail: the routing time orders the entire replay, so interpreting a naive
        // timestamp in the wrong zone silently reorders events against every other source.
        Path file = csv(HEADER, ROW_1);
        long utc = read(file, ZoneId.of("UTC")).seen.get(0).timestamp();
        long newYork = read(file, ZoneId.of("America/New_York")).seen.get(0).timestamp();

        assertThat(newYork).isNotEqualTo(utc);
        assertThat(newYork - utc).as("five hours in January")
                .isEqualTo(java.time.Duration.ofHours(5).toMillis());
    }

    @Test
    void trailingBlankRowsFromASpreadsheetExportAreSkippedNotErrors() throws Exception {
        // Exactly what the provided sample file ends with. The row count alone is not enough to prove
        // they were skipped: a blank row reaching the mapper fails the read only AFTER the good rows
        // have been published, so the absence of a failure is the real assertion.
        Collector collector = new Collector();
        CsvReaderGateway<CdfBar> reader = new CsvReaderGateway<>(
                "reader", BARS, List.of("CL"),
                csv(HEADER, ROW_1, ROW_2, ",,,,,,,,,", ",,,,,,,,,", ""),
                new CdfBarCsvMapper(ZoneId.of("UTC")), START, END);
        reader.registerSubscriber(collector, BARS, List.of("CL"));
        reader.run();

        assertThat(collector.seen).hasSize(2);
        assertThat(reader.terminalFailure()).as("a blank row is not an error").isEmpty();
        assertThat(reader.rowsSkipped()).isGreaterThanOrEqualTo(2);
        assertThat(reader.rowsRead()).as("blank rows are not counted as content").isEqualTo(2);
    }

    @Test
    void rowsAreFilteredByKeyAndWindow() throws Exception {
        String other = "1/6/2021,1/6/2021 23:00,1,1,1,1,1,1,1,BZ";
        Collector c = read(csv(HEADER, ROW_1, other, ROW_2), ZoneId.of("UTC"));
        assertThat(c.seen).extracting(CdfBar::symb).containsExactly("CL", "CL");
    }

    // ------------------------------------------------------------------
    // Failure
    // ------------------------------------------------------------------

    @Test
    void anUnmappableRowFailsTheReadAndNamesTheLine() throws Exception {
        Path file = csv(HEADER, ROW_1, "1/5/2021,1/5/2021 23:00,NOT_A_NUMBER,1,1,1,1,1,1,CL");
        Collector collector = new Collector();
        CsvReaderGateway<CdfBar> reader = new CsvReaderGateway<>(
                "reader", BARS, List.of("CL"), file, new CdfBarCsvMapper(ZoneId.of("UTC")),
                START, END);
        reader.setFailureIsFatal(true);
        reader.registerSubscriber(collector, BARS, List.of("CL"));
        reader.run();

        // Reported, not just logged - otherwise a half-loaded file looks like a completed one (§8).
        assertThat(reader.terminalFailure()).isPresent();
        assertThat(reader.terminalFailure().get().fatal()).isTrue();
        assertThat(reader.terminalFailure().get().cause().getMessage()).contains("line 3");
        assertThat(collector.seen).as("the good row before it was still delivered").hasSize(1);
        assertThat(reader.status()).as("and it disconnected, so the run cannot hang")
                .isEqualTo(GatewayStatus.STOPPED);
    }

    @Test
    void aCleanReadReportsNoFailure() throws Exception {
        CsvReaderGateway<CdfBar> reader = new CsvReaderGateway<>(
                "reader", BARS, List.of("CL"), csv(HEADER, ROW_1),
                new CdfBarCsvMapper(ZoneId.of("UTC")), START, END);
        reader.registerSubscriber(new Collector(), BARS, List.of("CL"));
        reader.run();
        assertThat(reader.terminalFailure()).as("end of file is not a failure").isEmpty();
        assertThat(reader.rowsRead()).isEqualTo(1);
    }

    @Test
    void aMissingRequiredColumnIsReportedAgainstItsLine() throws Exception {
        Path file = csv(HEADER, "1/4/2021,,39.64,40.99,39.18,39.49,39.79,1,1,CL");
        CsvReaderGateway<CdfBar> reader = new CsvReaderGateway<>(
                "reader", BARS, List.of("CL"), file, new CdfBarCsvMapper(ZoneId.of("UTC")),
                START, END);
        reader.registerSubscriber(new Collector(), BARS, List.of("CL"));
        reader.run();
        assertThat(reader.terminalFailure()).isPresent();
        assertThat(reader.terminalFailure().get().cause().getMessage()).contains("datetime");
    }

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    @Test
    void quotedFieldsAndEscapedQuotesAreHandled() {
        assertThat(CsvReaderGateway.parseLine("a,\"b,c\",d")).containsExactly("a", "b,c", "d");
        assertThat(CsvReaderGateway.parseLine("\"say \"\"hi\"\"\",x"))
                .containsExactly("say \"hi\"", "x");
        assertThat(CsvReaderGateway.parseLine("a,,c")).containsExactly("a", "", "c");
    }

    @Test
    void decimalsComeFromTheTextNotFromADouble() throws Exception {
        // This file's own values are short enough that a double would survive them, so the guarantee
        // has to be pinned with a value that cannot: 20 significant digits. Going via double here would
        // lose precision before the database is even involved.
        String precise = "1/4/2021,1/4/2021 23:00,12345.678901234567891,1,1,1,,,1,CL";
        Collector c = read(csv(HEADER, precise), ZoneId.of("UTC"));

        assertThat(c.seen).hasSize(1);
        assertThat(c.seen.get(0).op())
                .isEqualByComparingTo(new BigDecimal("12345.678901234567891"));
    }

    @Test
    void optionalColumnsMayBeEmpty() throws Exception {
        // vwap and count are optional in CdfBar; blank must mean absent, not zero.
        Path file = csv(HEADER, "1/4/2021,1/4/2021 23:00,39.64,40.99,39.18,39.49,,,23204,CL");
        Collector c = read(file, ZoneId.of("UTC"));
        assertThat(c.seen).hasSize(1);
        assertThat(c.seen.get(0).vwap()).isNull();
        assertThat(c.seen.get(0).count()).isNull();
    }
}
