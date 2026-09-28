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

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The sink's three-outcome accounting and the identity that makes a retry safe.
 *
 * <p>The thing under test is honesty rather than throughput: an outcome the client cannot establish
 * must stay {@code unknown} until something proves otherwise, and the books must balance so a run
 * manifest can be read as fact.
 */
class DeliverySemanticsTest {

    // ------------------------------------------------------------------
    // Three outcomes
    // ------------------------------------------------------------------

    @Test
    void unknownIsNeverFoldedIntoEitherNeighbour() {
        DeliveryAccounting a = new DeliveryAccounting();
        a.observe(30);
        a.record(10, DeliveryOutcome.COMMITTED);
        a.record(10, DeliveryOutcome.NOT_COMMITTED);
        a.record(10, DeliveryOutcome.UNKNOWN);

        DeliveryAccounting.Counts c = a.counts();
        assertThat(c.committed()).as("not inflated by the unknown batch").isEqualTo(10);
        assertThat(c.notCommitted()).as("not inflated by the unknown batch").isEqualTo(10);
        assertThat(c.unknown()).isEqualTo(10);
        assertThat(c.hasFailures()).isTrue();
    }

    @Test
    void theBooksBalanceAcrossEveryKindOfLoss() {
        DeliveryAccounting a = new DeliveryAccounting();
        a.observe(100);
        a.record(60, DeliveryOutcome.COMMITTED);
        a.record(5,  DeliveryOutcome.NOT_COMMITTED);
        a.record(5,  DeliveryOutcome.UNKNOWN);
        a.overflow(20);
        a.serializationError(4);

        DeliveryAccounting.Counts c = a.counts();
        assertThat(c.balances()).isTrue();
        assertThat(c.inFlight()).as("100 observed, 94 accounted for").isEqualTo(6);
    }

    @Test
    void anUnknownRunEndsSayingSo() {
        // The case this whole design exists for: the commit landed, the acknowledgement did not, and
        // the run ends without ever learning which. The manifest must not round that to either side.
        DeliveryAccounting a = new DeliveryAccounting();
        a.observe(10);
        a.record(10, DeliveryOutcome.UNKNOWN);

        DeliveryAccounting.Counts c = a.counts();
        assertThat(c.unknown()).isEqualTo(10);
        assertThat(c.committed()).isZero();
        assertThat(c.notCommitted()).isZero();
        assertThat(c.balances()).isTrue();
        assertThat(c.inFlight()).isZero();
    }

    @Test
    void aRetryResolvesUnknownWithoutChangingTheTotal() {
        DeliveryAccounting a = new DeliveryAccounting();
        a.observe(10);
        a.record(10, DeliveryOutcome.UNKNOWN);

        // The unique violation on the ingestion id proves the first attempt committed.
        a.resolveUnknown(10, DeliveryOutcome.COMMITTED);

        DeliveryAccounting.Counts c = a.counts();
        assertThat(c.unknown()).isZero();
        assertThat(c.committed()).isEqualTo(10);
        assertThat(c.observed()).as("resolution moves events, it does not create them").isEqualTo(10);
        assertThat(c.balances()).isTrue();
    }

    @Test
    void resolvingMoreThanIsOutstandingIsRefusedAndLeavesTheBooksIntact() {
        DeliveryAccounting a = new DeliveryAccounting();
        a.observe(5);
        a.record(5, DeliveryOutcome.UNKNOWN);

        assertThatThrownBy(() -> a.resolveUnknown(6, DeliveryOutcome.COMMITTED))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(a.counts().unknown()).as("the failed resolution must not corrupt the count")
                .isEqualTo(5);
        assertThat(a.counts().committed()).isZero();
    }

    @Test
    void unknownCannotResolveIntoItself() {
        DeliveryAccounting a = new DeliveryAccounting();
        a.observe(1);
        a.record(1, DeliveryOutcome.UNKNOWN);
        assertThatThrownBy(() -> a.resolveUnknown(1, DeliveryOutcome.UNKNOWN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown");
    }

    @Test
    void countersStayExactUnderConcurrentWriters() throws Exception {
        // The writer path is concurrent with the reporting path; a lost update here would understate
        // what was dropped, which is the one direction the manifest must never err in.
        DeliveryAccounting a = new DeliveryAccounting();
        int threads = 8, perThread = 2_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    go.await();
                    for (int i = 0; i < perThread; i++) {
                        a.observe(1);
                        a.record(1, DeliveryOutcome.COMMITTED);
                    }
                    return null;
                });
            }
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        DeliveryAccounting.Counts c = a.counts();
        assertThat(c.observed()).isEqualTo((long) threads * perThread);
        assertThat(c.committed()).isEqualTo((long) threads * perThread);
        assertThat(c.balances()).isTrue();
    }

    @Test
    void negativeCountsAreRefusedRatherThanQuietlyUnwindingTheBooks() {
        DeliveryAccounting a = new DeliveryAccounting();
        assertThatThrownBy(() -> a.observe(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> a.overflow(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> a.record(-1, DeliveryOutcome.COMMITTED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyUnknownIsUnresolved() {
        assertThat(DeliveryOutcome.COMMITTED.isResolved()).isTrue();
        assertThat(DeliveryOutcome.NOT_COMMITTED.isResolved()).isTrue();
        assertThat(DeliveryOutcome.UNKNOWN.isResolved()).isFalse();
    }

    // ------------------------------------------------------------------
    // Ingestion identity
    // ------------------------------------------------------------------

    @Test
    void theSameEventYieldsTheSameIdOnEveryAttempt() {
        // This is the entire basis of retry safety: if it were not deterministic, a retry would insert
        // a duplicate instead of violating the constraint.
        IngestionId first  = new IngestionId("2026-09-28T10-00-00Z-abc", 42);
        IngestionId retry  = new IngestionId("2026-09-28T10-00-00Z-abc", 42);
        assertThat(retry).isEqualTo(first);
        assertThat(retry.encoded()).isEqualTo(first.encoded());
    }

    @Test
    void distinctEventsNeverShareAnId() {
        IngestionId a = new IngestionId("run-a", 1);
        IngestionId b = new IngestionId("run-a", 2);
        IngestionId c = new IngestionId("run-b", 1);
        assertThat(a.encoded()).isNotEqualTo(b.encoded());
        assertThat(a.encoded()).isNotEqualTo(c.encoded());
    }

    @Test
    void anIdRoundTripsThroughItsStoredForm() {
        IngestionId id = new IngestionId("2026-09-28T10-00-00Z-abc", 987654321L);
        assertThat(IngestionId.parse(id.encoded())).isEqualTo(id);
    }

    @Test
    void aRunIdCarryingTheSeparatorIsRefused() {
        // Otherwise ("a:b", 1) and ("a", ...) could encode alike and the uniqueness the retry argument
        // rests on would not hold.
        assertThatThrownBy(() -> new IngestionId("run:a", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("':'");
    }

    @Test
    void emptyOrNegativePartsAreRefused() {
        assertThatThrownBy(() -> new IngestionId("", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IngestionId("run", -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void garbageDoesNotParseIntoAnId() {
        assertThatThrownBy(() -> IngestionId.parse("no-separator-here"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IngestionId.parse("run:not-a-number"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void idsOrderByRunThenSequence() {
        assertThat(new IngestionId("run", 2)).isGreaterThan(new IngestionId("run", 1));
        assertThat(new IngestionId("run-a", 99)).isLessThan(new IngestionId("run-b", 1));
    }

    // ------------------------------------------------------------------
    // Failure policy
    // ------------------------------------------------------------------

    @Test
    void theDefaultPolicyDoesNotKillTheRun() {
        // A sink that can stop a run is not something to acquire by accident.
        assertThat(SinkFailurePolicy.OBSERVATIONAL.isFatal()).isFalse();
        assertThat(SinkFailurePolicy.ESSENTIAL.isFatal()).isTrue();
    }
}
