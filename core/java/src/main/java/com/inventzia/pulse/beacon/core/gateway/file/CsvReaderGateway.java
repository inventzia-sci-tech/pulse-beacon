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
import com.inventzia.pulse.beacon.core.Gateway;
import com.inventzia.pulse.beacon.core.GatewayStatus;
import com.inventzia.pulse.beacon.core.Topic;
import com.inventzia.pulse.data.datum.Datum;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A clock-driving gateway that reads a header-bearing CSV file and publishes each qualifying row.
 *
 * <p>Sibling of {@link JsonlReaderGateway}, and deliberately shaped the same way. The difference is
 * that a CSV row is not self-describing: there is no type tag and no field types, so the caller
 * supplies a {@link RowMapper} that turns one row into a datum. That keeps this class free of any
 * particular file's conventions — date formats, time zones, column names — which belong to whoever
 * knows what the file means.
 *
 * <h2>What it does not try to be</h2>
 * <p>Not a general CSV library. It handles a header row, quoted fields containing commas or doubled
 * quotes, and blank rows; it does not handle embedded newlines inside quoted fields. A file needing
 * more than that should be converted first, rather than have this grow into a parser nobody tested.
 *
 * <h2>Blank rows are skipped, not errors</h2>
 * <p>Spreadsheet exports routinely end with rows of bare commas. Those are skipped silently. A row
 * with content that the mapper cannot convert is a different matter and fails the read.
 *
 * <h2>Failure</h2>
 * <p>On any failure the gateway disconnects — releasing the TimeMachine's all-drivers barrier so the
 * run terminates rather than hanging — <em>and</em> reports the failure through
 * {@link AbstractGateway#failTerminally}, because disconnecting alone is indistinguishable from
 * reaching the end of the file. Whether that ends the run is the launcher's choice, via
 * {@code setFailureIsFatal}. See {@code docs/pulse-sql-gateway.md} §8.
 *
 * @param <P> the payload type this gateway produces
 */
public final class CsvReaderGateway<P extends Datum> extends AbstractGateway {

    /** Turns one CSV row, keyed by header name, into a datum. */
    @FunctionalInterface
    public interface RowMapper<P extends Datum> {
        /**
         * @param row    the row's values by column name, in file order
         * @param lineNo the 1-based line number, for error messages
         * @return the datum, or {@code null} to skip this row
         */
        P map(Map<String, String> row, long lineNo);
    }

    private final Topic<P>     topic;
    private final List<String> keys;
    private final Path         filePath;
    private final RowMapper<P> mapper;

    private volatile long rowsRead      = 0;
    private volatile long rowsPublished = 0;
    private volatile long rowsSkipped   = 0;

    /**
     * @param name      gateway instance name
     * @param topic     the topic on which events are published
     * @param keys      key values to publish; rows with other keys are skipped. Empty means all.
     * @param filePath  path to the CSV file
     * @param mapper    converts a row into a datum
     * @param startTime epoch millis — events before this are skipped
     * @param endTime   epoch millis — the reader stops when an event exceeds this
     */
    public CsvReaderGateway(String name, Topic<P> topic, List<String> keys, Path filePath,
                            RowMapper<P> mapper, long startTime, long endTime) {
        super(name, startTime, endTime);
        this.topic    = Objects.requireNonNull(topic, "topic");
        this.keys     = List.copyOf(Objects.requireNonNull(keys, "keys"));
        this.filePath = Objects.requireNonNull(filePath, "filePath");
        this.mapper   = Objects.requireNonNull(mapper, "mapper");
        setDriveClock(true);
    }

    @Override
    public void run() {
        initialize();
        connect();
        setStatus(GatewayStatus.STARTED);

        try (BufferedReader reader = Files.newBufferedReader(filePath)) {
            String headerLine = nextContentLine(reader);
            if (headerLine == null) {
                throw new IllegalStateException(filePath + " is empty; expected a header row");
            }
            List<String> header = parseLine(headerLine);
            long lineNo = 1;
            String line;

            while ((line = reader.readLine()) != null && connected()) {
                lineNo++;
                if (isBlankRow(line)) {
                    rowsSkipped++;          // trailing commas from a spreadsheet export
                    continue;
                }
                rowsRead++;
                P payload = mapper.map(toRow(header, parseLine(line), lineNo), lineNo);
                if (payload == null) {
                    rowsSkipped++;
                    continue;
                }
                if (!keys.isEmpty() && !keys.contains(payload.getDatumKey())) continue;
                if (payload.getDatumTime() < startTime())                     continue;
                if (payload.getDatumTime() > endTime())                       break;

                Gateway downstream = subscriberForKey(topic, payload.getDatumKey());
                if (downstream != null) {
                    downstream.onEvent(topic, payload);
                    rowsPublished++;
                }
            }
        } catch (Exception e) {
            log.severe("gateway '" + name() + "' failed reading " + filePath
                    + " after " + rowsRead + " rows; disconnecting so the run can terminate", e);
            // Disconnecting is also what a healthy reader does at end of file, so on its own it tells
            // the engine nothing. This is what distinguishes a failure from a clean finish.
            failTerminally("failed reading " + filePath + " after " + rowsRead + " rows", e);
        } finally {
            disconnect();
            setStatus(GatewayStatus.STOPPED);
        }
    }

    /** The first line with any content, so a leading blank line is not mistaken for the header. */
    private static String nextContentLine(BufferedReader reader) throws java.io.IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            if (!line.isBlank()) return line;
        }
        return null;
    }

    /** Whether a line is empty or nothing but separators — the shape a spreadsheet export leaves. */
    private static boolean isBlankRow(String line) {
        return line.isBlank() || line.chars().allMatch(c -> c == ',' || c == ' ' || c == '\t'
                                                            || c == '"' || c == '\r');
    }

    private static Map<String, String> toRow(List<String> header, List<String> values, long lineNo) {
        Map<String, String> row = new LinkedHashMap<>();
        for (int i = 0; i < header.size(); i++) {
            row.put(header.get(i), i < values.size() ? values.get(i) : "");
        }
        return row;
    }

    /**
     * Split one CSV line, honouring double-quoted fields and {@code ""} as an escaped quote.
     *
     * <p>Embedded newlines are out of scope — see the class note on what this deliberately is not.
     */
    public static List<String> parseLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(ch);
                }
            } else if (ch == '"') {
                inQuotes = true;
            } else if (ch == ',') {
                out.add(field.toString().trim());
                field.setLength(0);
            } else if (ch != '\r') {
                field.append(ch);
            }
        }
        out.add(field.toString().trim());
        return out;
    }

    /** Rows with content that were read (excluding blank rows). */
    public long rowsRead() { return rowsRead; }

    /** Rows actually published, after the key and window filters. */
    public long rowsPublished() { return rowsPublished; }

    /** Blank rows and rows the mapper chose to skip. */
    public long rowsSkipped() { return rowsSkipped; }

    /** A source does not receive events. */
    @Override
    public <Q extends Datum> void onEvent(Topic<Q> topic, Q payload) {
        throw new UnsupportedOperationException(name() + ": CsvReaderGateway does not receive events");
    }

    @Override
    @SuppressWarnings("unchecked")
    public <Q extends Datum> void publish(Topic<Q> t, Q payload) {
        if (!t.equals(this.topic)) {
            throw new IllegalArgumentException(name() + ": unknown topic " + t.name());
        }
        Gateway downstream = subscriberForKey(this.topic, payload.getDatumKey());
        if (downstream != null) {
            downstream.onEvent(this.topic, (P) payload);
        }
    }
}
