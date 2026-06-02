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
package com.inventzia.beacon.core;

import java.util.Comparator;

/**
 * Orders {@link TimeEvent}s for the {@link TimeMachine} queue.
 *
 * <p>Primary key: {@link Event#eventTime()} ascending — events are processed
 * in the order of the wall-clock time they represent, not the order they
 * arrived. This is the correct key for deterministic causal replay.
 *
 * <p>Tiebreaker: {@link TimeEvent#beginTstamp()} ascending — when two events
 * share the same {@code eventTime}, the one that was received earlier by its
 * gateway is processed first.
 */
final class TimeEventComparator implements Comparator<TimeEvent> {

    @Override
    public int compare(TimeEvent a, TimeEvent b) {
        int c = Long.compare(a.event().eventTime(), b.event().eventTime());
        if (c != 0) return c;
        return Long.compare(a.beginTstamp(), b.beginTstamp());
    }
}
