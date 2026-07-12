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

import java.util.Comparator;

/**
 * Orders {@link TimeEvent}s for the {@link TimeMachine} queue.
 *
 * <p>Primary key: {@link TimeEvent#eventTime()} ascending — events are
 * processed in the order of the wall-clock time they represent, not the order
 * they arrived. This is the correct key for deterministic causal replay.
 *
 * <p>Tie-break (all deterministic, so equal-{@code eventTime} events order the
 * same on every run/JVM rather than by thread arrival):
 * <ol>
 *   <li>{@link TimeEvent#originRank()} — high-priority origins (rank 0) before
 *       normal (rank 1);</li>
 *   <li>{@link TimeEvent#originIndex()} — the origin's stable registration index;</li>
 *   <li>{@link TimeEvent#sequence()} — enqueue order, which disambiguates several
 *       events from one non-permit-gated origin at the same tick (e.g. actor
 *       publishes). The write permit already guarantees at most one pending event
 *       per clock-driving gateway, so rank+index alone fully order those.</li>
 * </ol>
 */
final class TimeEventComparator implements Comparator<TimeEvent> {

    @Override
    public int compare(TimeEvent a, TimeEvent b) {
        int c = Long.compare(a.eventTime(), b.eventTime());
        if (c != 0) return c;
        c = Integer.compare(a.originRank(), b.originRank());
        if (c != 0) return c;
        c = Integer.compare(a.originIndex(), b.originIndex());
        if (c != 0) return c;
        return Long.compare(a.sequence(), b.sequence());
    }
}
