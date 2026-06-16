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
package com.inventzia.pulse.beacon.core.crosslanguage;

/**
 * One unit handed across the language boundary, in either direction.
 *
 * <p>A {@code DATA} event carries a topic name and the datum as a
 * self-describing tagged JSON envelope (see {@code DatumCodec.toTaggedJson}) —
 * the boundary contract is the pair {@code (topicName, taggedJson)}.
 *
 * <p>Lifecycle is carried as a transport-level {@link Kind} rather than as an
 * engine {@code EngineCommand}: lifecycle signalling is a property of the
 * bridge, and pulse-data's codec/registry know nothing of pulse-beacon's
 * command types. {@code START}/{@code STOP} carry the simulation time;
 * {@code END} terminates a streamer loop.
 */
public record CrossLanguageEvent(Kind kind, String topicName, String taggedJson, long time) {

    /** What this event means to the receiving streamer loop. */
    public enum Kind {
        /** A datum delivery: {@code topicName} + {@code taggedJson} are set. */
        DATA,
        /** Lifecycle start at {@code time}: map to the component's on_start. */
        START,
        /** Lifecycle stop at {@code time}: map to the component's on_stop. */
        STOP,
        /** End of stream: the streamer loop should terminate. */
        END
    }

    /** Sole end-of-stream sentinel. */
    public static final CrossLanguageEvent END = new CrossLanguageEvent(Kind.END, null, null, 0L);

    /** A datum delivery carrying its topic name and tagged-JSON envelope. */
    public static CrossLanguageEvent data(String topicName, String taggedJson) {
        return new CrossLanguageEvent(Kind.DATA, topicName, taggedJson, 0L);
    }

    /** A start-of-run lifecycle signal at {@code time} (epoch millis). */
    public static CrossLanguageEvent start(long time) {
        return new CrossLanguageEvent(Kind.START, null, null, time);
    }

    /** An end-of-run lifecycle signal at {@code time} (epoch millis). */
    public static CrossLanguageEvent stop(long time) {
        return new CrossLanguageEvent(Kind.STOP, null, null, time);
    }
}
