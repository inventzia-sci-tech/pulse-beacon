/*
 * SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
 * Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.
 *
 * This file is part of pulse-beacon.
 *
 * pulse-beacon is dual-licensed:
 *   - Under the GNU Affero General Public License v3.0 or later (see LICENSE-AGPL-3.0).
 *   - Under a commercial license (see LICENSE-COMMERCIAL.txt).
 *     Contact operations@inventzia.com.
 */
package com.inventzia.pulse.beacon.core.gateway.file;

import com.inventzia.pulse.beacon.core.AbstractGateway;
import com.inventzia.pulse.beacon.core.Gateway;
import com.inventzia.pulse.beacon.core.GatewayStatus;
import com.inventzia.pulse.beacon.core.Topic;
import com.inventzia.pulse.data.datum.Datum;
import com.inventzia.pulse.data.datum.DatumCodec;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * A clock-driving gateway that reads a JSONL (JSON Lines) file and publishes
 * each qualifying event onto the bus.
 *
 * <p>Each non-blank line must be a complete JSON object that the
 * {@link DatumCodec} can deserialise to the payload type {@code P} carried by
 * the configured {@link Topic}. Deserialisation failures throw
 * {@link com.inventzia.pulse.data.datum.DatumCodecException}.
 *
 * <h2>Time window</h2>
 * <ul>
 *   <li>Events with {@code eventTime < startTime} are silently skipped.</li>
 *   <li>The first event with {@code eventTime > endTime} causes the reader to
 *       stop and disconnect, which signals the engine to begin its shutdown
 *       sequence for this gateway's time window.</li>
 * </ul>
 *
 * <h2>Key filtering</h2>
 * <p>Only payloads whose {@link Datum#getDatumKey()} appears in the configured
 * key list are published. Others are silently skipped, so a single
 * JSONL file can contain multiple instruments and each reader only sees its own.
 *
 * <h2>Clock driving</h2>
 * <p>{@link #drivesClock()} is {@code true} by default. This ensures the
 * {@link com.inventzia.pulse.beacon.core.TimeMachine} waits for this reader before
 * dispatching events from other sources, preserving causal ordering.
 * Override with {@link #setDriveClock(boolean)} before the engine registers
 * this gateway.
 *
 * <h2>Immutability</h2>
 * <p>Generated event types are immutable records, so events are replayed
 * exactly as stored. Time mutation (floor/override) is not supported here;
 * pre-process the JSONL file if you need to re-stamp event times.
 *
 * @param <P> the payload type this gateway produces
 */
public final class JsonlReaderGateway<P extends Datum> extends AbstractGateway {

    private final Topic<P>     topic;
    private final List<String> keys;
    private final Path         filePath;
    private final DatumCodec   codec = DatumCodec.instance();

    /**
     * @param name      human-readable name for this gateway instance
     * @param topic     the topic on which events are published
     * @param keys      key values to publish; events with other keys are skipped
     * @param filePath  path to the JSONL file
     * @param startTime epoch millis — events before this time are skipped
     * @param endTime   epoch millis — the reader stops when an event exceeds this
     */
    public JsonlReaderGateway(String name,
                              Topic<P> topic,
                              List<String> keys,
                              Path filePath,
                              long startTime,
                              long endTime) {
        super(name, startTime, endTime);
        this.topic    = Objects.requireNonNull(topic,    "topic");
        this.keys     = List.copyOf(Objects.requireNonNull(keys, "keys"));
        this.filePath = Objects.requireNonNull(filePath, "filePath");
        setDriveClock(true);
    }

    // ------------------------------------------------------------------
    // Runnable — main read loop
    // ------------------------------------------------------------------

    @Override
    public void run() {
        initialize();
        connect();
        setStatus(GatewayStatus.STARTED);

        try (BufferedReader reader = Files.newBufferedReader(filePath)) {
            String line;
            while ((line = reader.readLine()) != null && connected()) {
                if (line.isBlank()) continue;

                P payload = parse(line);

                if (!keys.contains(payload.getDatumKey()))  continue; // key filter
                if (payload.getDatumTime() < startTime())   continue; // before window
                if (payload.getDatumTime() > endTime())     break;    // past window

                Gateway downstream = subscriberForKey(topic, payload.getDatumKey());
                if (downstream != null) {
                    downstream.onEvent(topic, payload);
                }
            }
        } catch (IOException e) {
            throw new JsonlGatewayException(
                    "I/O error reading " + filePath, e);
        }

        disconnect();
        setStatus(GatewayStatus.STOPPED);
    }

    // ------------------------------------------------------------------
    // Pub / Sub — reader produces events, does not receive them
    // ------------------------------------------------------------------

    @Override
    public <F extends Datum> void publish(Topic<F> topic, F payload) {
        throw new UnsupportedOperationException(
                name() + ": JsonlReaderGateway does not accept publish() calls");
    }

    @Override
    public <F extends Datum> void onEvent(Topic<F> topic, F payload) {
        throw new UnsupportedOperationException(
                name() + ": JsonlReaderGateway does not accept incoming events");
    }

    // ------------------------------------------------------------------
    // Internal
    // ------------------------------------------------------------------

    private P parse(String line) {
        return codec.fromJson(line, topic.payloadType());
    }
}
