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

/**
 * The delivery sink behind pulse-beacon's logging and reporting.
 *
 * <p>Platform components never call a logging framework directly. They log
 * through a {@link ComponentReporter} bound to their name, which forwards to a
 * {@code Reporter}. The default sink, {@link Slf4jReporter}, routes to SLF4J
 * (Logback by default), giving each component its own named logger. Alternative
 * sinks can deliver the same stream elsewhere — an alert feed, a monitoring
 * system, an in-memory buffer for tests — without changing any component.
 *
 * <p>Implementations must be thread-safe.
 */
public interface Reporter {

    /**
     * Deliver a message.
     *
     * @param timestamp epoch milliseconds of the event (platform/simulation
     *                  time); sinks may embed or ignore it
     * @param source    name of the component reporting (its logger name)
     * @param message   the message
     * @param level     severity
     */
    void report(long timestamp, String source, String message, ReportLevel level);

    /**
     * Whether messages at {@code level} from {@code source} would be delivered.
     * Lets callers skip building expensive messages that would be dropped.
     * Defaults to {@code true}; sinks backed by a level-aware framework should
     * override.
     *
     * @param source the component name
     * @param level  the severity to test
     * @return {@code true} if such a message would be delivered
     */
    default boolean isEnabled(String source, ReportLevel level) {
        return true;
    }
}
