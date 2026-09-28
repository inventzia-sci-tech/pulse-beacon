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
import java.util.concurrent.atomic.AtomicLong;

/**
 * The sink's books: every event it observed, accounted for by outcome.
 *
 * <p>Modelled on the recorder's accounting identity, which exists so that a run manifest can be read
 * as a statement of fact rather than a hopeful summary. The identity here is
 *
 * <pre>observed = committed + notCommitted + unknown + overflow + serializationErrors + inFlight</pre>
 *
 * <p>and {@link Counts#balances()} checks it. An identity that does not balance means the sink lost
 * track of events, which is exactly the failure a persistence gateway must not be able to hide.
 *
 * <p>{@code unknown} is never folded into a neighbour, and it is not necessarily terminal:
 * {@link #resolveUnknown(long, DeliveryOutcome)} moves a batch out of it once a retry carrying the same
 * {@link IngestionId} establishes what actually happened. A run that ends with {@code unknown > 0} ended
 * genuinely not knowing, and the manifest says so. See {@code docs/pulse-sql-gateway.md} §7.
 *
 * <p>Thread-safe: counters are updated from the writer thread and read from the run's reporting path.
 */
public final class DeliveryAccounting {

    private final AtomicLong observed            = new AtomicLong(); // every event the sink was handed
    private final AtomicLong committed           = new AtomicLong(); // commit acknowledged
    private final AtomicLong notCommitted        = new AtomicLong(); // demonstrably failed or rolled back
    private final AtomicLong unknown             = new AtomicLong(); // outcome not establishable
    private final AtomicLong overflow            = new AtomicLong(); // queue full, never enqueued
    private final AtomicLong serializationErrors = new AtomicLong(); // could not be turned into a row

    /** A point-in-time snapshot of the books. */
    public record Counts(long observed, long committed, long notCommitted, long unknown,
                         long overflow, long serializationErrors) {

        /** Events handed to the sink whose fate is not yet decided. */
        public long inFlight() {
            return observed - committed - notCommitted - unknown - overflow - serializationErrors;
        }

        /**
         * Whether the books balance.
         *
         * <p>False means the sink lost events without counting them — a bug in the sink, not in the
         * database, and one that would otherwise surface as a manifest quietly understating what was
         * dropped.
         */
        public boolean balances() {
            return inFlight() >= 0;
        }

        /** Whether anything at all failed to reach the database, {@code unknown} included. */
        public boolean hasFailures() {
            return notCommitted > 0 || unknown > 0 || overflow > 0 || serializationErrors > 0;
        }
    }

    /** One more event handed to the sink. */
    public void observe(long n) {
        observed.addAndGet(requireNonNegative(n));
    }

    /** Record the outcome of a batch of {@code n} events. */
    public void record(long n, DeliveryOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        counterFor(outcome).addAndGet(requireNonNegative(n));
    }

    /**
     * Move {@code n} events out of {@code unknown} now that a retry established what happened.
     *
     * @param resolved the established outcome; must not itself be {@link DeliveryOutcome#UNKNOWN}
     * @throws IllegalArgumentException if more events are resolved than are outstanding, which would
     *                                  mean the sink double-counted a resolution
     */
    public void resolveUnknown(long n, DeliveryOutcome resolved) {
        requireNonNegative(n);
        if (!Objects.requireNonNull(resolved, "resolved").isResolved()) {
            throw new IllegalArgumentException("cannot resolve unknown into unknown");
        }
        long remaining = unknown.addAndGet(-n);
        if (remaining < 0) {
            unknown.addAndGet(n);   // leave the books as they were rather than corrupt them
            throw new IllegalArgumentException(
                    "resolving " + n + " events but only " + (remaining + n) + " are unknown");
        }
        counterFor(resolved).addAndGet(n);
    }

    /** Events dropped because the sink's queue was full. */
    public void overflow(long n) {
        overflow.addAndGet(requireNonNegative(n));
    }

    /** Events that could not be turned into a row at all. */
    public void serializationError(long n) {
        serializationErrors.addAndGet(requireNonNegative(n));
    }

    /** A consistent-enough snapshot for reporting; not a linearizable read of all six counters. */
    public Counts counts() {
        return new Counts(observed.get(), committed.get(), notCommitted.get(), unknown.get(),
                overflow.get(), serializationErrors.get());
    }

    private AtomicLong counterFor(DeliveryOutcome outcome) {
        return switch (outcome) {
            case COMMITTED     -> committed;
            case NOT_COMMITTED -> notCommitted;
            case UNKNOWN       -> unknown;
        };
    }

    private static long requireNonNegative(long n) {
        if (n < 0) throw new IllegalArgumentException("count must not be negative: " + n);
        return n;
    }

    @Override
    public String toString() {
        Counts c = counts();
        return "DeliveryAccounting[observed=" + c.observed() + ", committed=" + c.committed()
               + ", notCommitted=" + c.notCommitted() + ", unknown=" + c.unknown()
               + ", overflow=" + c.overflow() + ", serializationErrors=" + c.serializationErrors() + "]";
    }
}
