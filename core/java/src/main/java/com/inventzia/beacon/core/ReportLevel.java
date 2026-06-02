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
 * Severity levels for the platform's out-of-band {@link Reporter} channel.
 *
 * <p>These levels are distinct from the application's logging framework
 * (e.g. SLF4J). Logging goes to log files; reporting goes to an operator
 * channel such as a monitoring system or an alert feed. Gateways and the
 * engine use {@code ReportLevel} to decide which events are worth surfacing
 * to an operator in real time.
 *
 * <p>The ordinal reflects increasing severity: a filter set to
 * {@link #WARNING} will pass {@code WARNING}, {@code SEVERE}, and
 * {@code FATAL} but suppress {@code INFO} and {@code LARGEINFO}.
 */
public enum ReportLevel {

    /** Verbose diagnostic output; only useful during deep debugging. */
    LARGEINFO,

    /** Normal operational information. */
    INFO,

    /** Something unexpected but recoverable. */
    WARNING,

    /** A serious error that may affect correctness or data integrity. */
    SEVERE,

    /** An unrecoverable condition; the component cannot continue. */
    FATAL;

    /**
     * Returns {@code true} if this level is at least as severe as
     * {@code threshold} — i.e. it should pass a filter set to {@code threshold}.
     *
     * @param threshold the minimum level to pass
     * @return whether this level meets or exceeds the threshold
     */
    public boolean meets(ReportLevel threshold) {
        return this.ordinal() >= threshold.ordinal();
    }
}
