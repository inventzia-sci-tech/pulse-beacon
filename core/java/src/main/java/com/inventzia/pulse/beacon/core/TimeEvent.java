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
package com.inventzia.pulse.beacon.core;

import com.inventzia.pulse.data.datum.Datum;

import java.util.Objects;

/**
 * The internal transport quantum of the pulse-beacon engine.
 *
 * <p>A {@code TimeEvent} wraps a single {@link Datum} payload with the routing
 * and timing context the engine needs to order, dispatch, and account for it.
 * It is an engine-internal type: subscribers receive the unwrapped payload via
 * {@link Sub#onEvent}; {@code TimeEvent} is never part of the public actor API.
 *
 * <p>{@link #topic()} uses a wildcard ({@code Topic<?>}) because the engine's
 * internal queue is heterogeneous — it holds payloads of many types
 * simultaneously. Type safety is recovered at the dispatch site, where the
 * engine casts once using {@link Topic#payloadType()} before calling
 * {@link Sub#onEvent}.
 *
 * <p>{@link #key()} and {@link #eventTime()} are <em>delegated</em> to the
 * payload's {@link Datum} contract rather than copied — a single source of
 * truth, with no risk of the envelope drifting from the data it carries.
 *
 * <p>{@link #origin()} is required by the {@code TimeMachine}: it tracks
 * per-gateway write permits and which clock-driving gateways have a pending
 * event. Both are keyed on the originating gateway.
 *
 * <p>Timestamps:
 * <ul>
 *   <li>{@link #beginTstamp()} — epoch millis when the gateway first received
 *       or produced the underlying data (wall-clock at read time).</li>
 *   <li>{@link #readTstamp()} — epoch millis when the event was placed into
 *       the {@code TimeMachine} queue.</li>
 * </ul>
 *
 * <p><b>Deterministic ordering.</b> {@link #originRank()}, {@link #originIndex()},
 * and {@link #sequence()} give the {@link TimeMachine} a stable tie-break for
 * events with equal {@link #eventTime()}, so a compressed-time replay is
 * reproducible rather than dependent on thread arrival: order by event time, then
 * by the origin's rank (high-priority before normal), then by the origin's stable
 * registration index, then by the enqueue sequence (disambiguates several events
 * from one non-permit-gated origin, e.g. actor publishes at the same tick). These
 * are unused by the real-time FIFO — see {@link #unordered}.
 */
public record TimeEvent(
        Datum    payload,
        Topic<?> topic,
        Gateway  origin,
        long     beginTstamp,
        long     readTstamp,
        int      originRank,
        int      originIndex,
        long     sequence
) {
    public TimeEvent {
        Objects.requireNonNull(payload, "payload must not be null");
        Objects.requireNonNull(topic,   "topic must not be null");
        Objects.requireNonNull(origin,  "origin must not be null");
    }

    /**
     * A {@code TimeEvent} for a queue that does not order by the merge comparator
     * (the real-time FIFO {@code liveQueue}); the ordering fields default to zero.
     */
    public static TimeEvent unordered(Datum payload, Topic<?> topic, Gateway origin,
                                      long beginTstamp, long readTstamp) {
        return new TimeEvent(payload, topic, origin, beginTstamp, readTstamp, 0, 0, 0L);
    }

    /** The routing key of the carried payload. */
    public String key() {
        return payload.getDatumKey();
    }

    /** The logical event time (epoch millis) of the carried payload. */
    public long eventTime() {
        return payload.getDatumTime();
    }
}
