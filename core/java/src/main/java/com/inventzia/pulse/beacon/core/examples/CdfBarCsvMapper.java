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

import com.inventzia.pulse.beacon.core.gateway.file.CsvReaderGateway;
import com.inventzia.pulse.data.schemas.marketdata.CdfBar;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Maps a daily OHLC CSV row onto {@link CdfBar}.
 *
 * <p>Written for a file with this header:
 * <pre>date,datetime,op,hi,lo,cl,vwap,count,vlm,symb</pre>
 * where dates are US-style {@code M/d/yyyy} and {@code datetime} is {@code M/d/yyyy H:mm}.
 *
 * <h2>The time zone has to be supplied, and that is not a detail</h2>
 * <p>The file's {@code datetime} carries <b>no zone</b>. {@code CdfBar}'s routing time is epoch
 * milliseconds, which is an absolute instant — so converting one to the other requires a zone, and the
 * choice moves every event by whole hours. That changes the replay's ordering against any other source,
 * and shifts which calendar day a bar belongs to.
 *
 * <p>There is no safe default to infer, so the zone is an explicit constructor argument: a caller must
 * say what the file means rather than discover later that something assumed on its behalf was wrong.
 * The examples default to UTC and print the zone they used.
 *
 * <h2>Decimals stay decimals</h2>
 * <p>Every price and volume is parsed as {@link BigDecimal} straight from its text. Going via
 * {@code double} would lose exactly what the storage mapping exists to preserve, before the database is
 * even involved.
 */
public final class CdfBarCsvMapper implements CsvReaderGateway.RowMapper<CdfBar> {

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("M/d/yyyy");
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("M/d/yyyy H:mm");

    private final ZoneId zone;

    /**
     * @param zone the zone the file's naive {@code datetime} values are expressed in
     */
    public CdfBarCsvMapper(ZoneId zone) {
        this.zone = java.util.Objects.requireNonNull(zone, "zone");
    }

    /** The zone used to turn naive timestamps into instants. */
    public ZoneId zone() {
        return zone;
    }

    @Override
    public CdfBar map(Map<String, String> row, long lineNo) {
        try {
            String symb = required(row, "symb", lineNo);
            LocalDateTime naive = LocalDateTime.parse(required(row, "datetime", lineNo), DATE_TIME);
            java.time.Instant when = naive.atZone(zone).toInstant();

            return new CdfBar(
                    symb,
                    when.toEpochMilli(),            // the routing time, derived from datetime + zone
                    decimal(row, "op", lineNo),
                    decimal(row, "hi", lineNo),
                    decimal(row, "lo", lineNo),
                    decimal(row, "cl", lineNo),
                    decimal(row, "vlm", lineNo),
                    optionalDecimal(row, "vwap"),
                    when,
                    optionalLong(row, "count"),
                    LocalDate.parse(required(row, "date", lineNo), DATE),
                    null,                            // expiry: not in this file
                    null,                            // strike: not in this file
                    null);                           // symExp: not in this file
        } catch (RuntimeException e) {
            // Name the line. A stack trace pointing at a parser tells nobody which row is wrong.
            throw new IllegalArgumentException(
                    "line " + lineNo + ": cannot map to CdfBar: " + e.getMessage(), e);
        }
    }

    private static String required(Map<String, String> row, String column, long lineNo) {
        String v = row.get(column);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("column '" + column + "' is missing or empty");
        }
        return v.trim();
    }

    /** Exact from text: never through {@code double}. */
    private static BigDecimal decimal(Map<String, String> row, String column, long lineNo) {
        return new BigDecimal(required(row, column, lineNo));
    }

    private static BigDecimal optionalDecimal(Map<String, String> row, String column) {
        String v = row.get(column);
        return v == null || v.isBlank() ? null : new BigDecimal(v.trim());
    }

    private static Long optionalLong(Map<String, String> row, String column) {
        String v = row.get(column);
        return v == null || v.isBlank() ? null : Long.valueOf(v.trim());
    }
}
