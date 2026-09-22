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
 * A side observer of the reporting stream. Attached to {@link Slf4jReporter#addTap} to receive a copy
 * of every message the reporter delivers, without changing where the reporter otherwise routes it.
 *
 * <p>This is how a per-run {@code console.log} is captured without coupling the engine to a logging
 * backend: the run orchestration attaches a tap that writes the records belonging to its run (selected
 * by the run-id in the SLF4J MDC) to the run's own file, then detaches it at run end. A tap is invoked
 * synchronously on the reporting thread, must be quick, and must not throw back into the reporter (the
 * reporter isolates a tap failure). Implementations must be thread-safe: taps may fire concurrently
 * from many component threads.
 */
@FunctionalInterface
public interface ReportTap {

    /**
     * Receive one reported message.
     *
     * @param timestamp epoch millis stamped by the reporting component (wall clock)
     * @param source    the reporting component's name
     * @param message   the message (may contain newlines, e.g. an appended stack trace)
     * @param level     severity
     */
    void onReport(long timestamp, String source, String message, ReportLevel level);
}
