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

/**
 * Out-of-band reporting channel for platform-level alerts and status messages.
 *
 * <p>Reporting is distinct from application logging. A {@code Reporter}
 * surfaces significant operational events — status transitions, warnings,
 * fatal conditions — to an operator channel such as a monitoring system,
 * an alert feed, or a dashboard. Routine diagnostic output belongs in the
 * application's logging framework (e.g. SLF4J), not here.
 *
 * <p>Implementations are free to filter, batch, or forward messages
 * asynchronously. They must be thread-safe.
 */
public interface Reporter {

    /**
     * Report an event.
     *
     * @param timestamp epoch milliseconds when the event occurred
     * @param source    human-readable name of the component reporting
     *                  (e.g. a gateway name or engine name)
     * @param message   a concise description of the event
     * @param level     severity of the event
     */
    void report(long timestamp, String source, String message, ReportLevel level);
}
