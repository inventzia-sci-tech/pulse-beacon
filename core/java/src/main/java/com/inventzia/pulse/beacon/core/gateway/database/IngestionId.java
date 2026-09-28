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

import java.util.Objects;

/**
 * The identity a row is deduplicated by on retry: deterministic, and deliberately not the natural key.
 *
 * <p>Derived from {@code (runId, sequence)}, so the same event produces the same id on every attempt.
 * Under a unique constraint that turns retry from a gamble into a decision procedure: the retry either
 * inserts, or violates the constraint — and the violation is <em>proof</em> that the earlier attempt
 * committed, resolving an {@link DeliveryOutcome#UNKNOWN} into {@link DeliveryOutcome#COMMITTED}.
 * Deduplication becomes a fact rather than a guess.
 *
 * <p><b>Why not the natural key.</b> The opt-in natural-key upsert (business key + event time) exists
 * for a different question: so that re-running a strategy converges instead of accumulating duplicates.
 * Reusing it for retry safety would silently overwrite two legitimately distinct events that happen to
 * share a business key and a timestamp — a real case, not a hypothetical, whenever an instrument
 * produces two ticks in the same millisecond. Both mechanisms are needed; neither substitutes for the
 * other. See {@code docs/pulse-sql-gateway.md} §7.
 *
 * <p><b>Why text and not a hash.</b> Both parts are already short and already unique together, so a
 * hash would buy nothing and cost the two things that matter when someone is staring at a half-written
 * table at three in the morning: you can read which run and which event a row came from, and there is
 * no collision to reason about.
 */
public record IngestionId(String runId, long sequence) implements Comparable<IngestionId> {

    /** The column this is stored in, and the column the unique constraint is on. */
    public static final String COLUMN = "_pulse_ingestion_id";

    /** Separator between the two parts. Not legal in a run id, so the encoding is unambiguous. */
    private static final char SEPARATOR = ':';

    public IngestionId {
        Objects.requireNonNull(runId, "runId");
        if (runId.isEmpty()) {
            throw new IllegalArgumentException("runId must not be empty");
        }
        if (runId.indexOf(SEPARATOR) >= 0) {
            // Otherwise two different (runId, sequence) pairs could encode to the same string, and the
            // uniqueness the whole retry argument rests on would not hold.
            throw new IllegalArgumentException(
                    "runId must not contain '" + SEPARATOR + "': " + runId);
        }
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must not be negative: " + sequence);
        }
    }

    /** The stored form: {@code <runId>:<sequence>}. Stable across retries, by construction. */
    public String encoded() {
        return runId + SEPARATOR + sequence;
    }

    /** Parse the stored form back, for reading a table the sink wrote. */
    public static IngestionId parse(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        int at = encoded.lastIndexOf(SEPARATOR);
        if (at < 0) {
            throw new IllegalArgumentException("not an ingestion id: " + encoded);
        }
        try {
            return new IngestionId(encoded.substring(0, at),
                    Long.parseLong(encoded.substring(at + 1)));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not an ingestion id: " + encoded, e);
        }
    }

    @Override
    public int compareTo(IngestionId other) {
        int byRun = runId.compareTo(other.runId);
        return byRun != 0 ? byRun : Long.compare(sequence, other.sequence);
    }

    @Override
    public String toString() {
        return encoded();
    }
}
