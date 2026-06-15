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

import java.util.Objects;
import java.util.function.Supplier;

/**
 * The per-component logging handle used throughout pulse-beacon.
 *
 * <p>Every engine, gateway, time machine, and actor owns a
 * {@code ComponentReporter} bound to its own {@code source} name. Components
 * log through this handle ({@code log.info("...")}) rather than touching any
 * logging framework directly — the underlying {@link Reporter} sink does the
 * actual delivery (SLF4J by default; see {@link Slf4jReporter}). The sink can
 * be swapped at runtime via {@link #sink(Reporter)} to redirect a component's
 * output (e.g. to an alert feed) without changing the component.
 *
 * <p>The convenience methods mirror {@link ReportLevel}. The
 * {@link #largeInfo(Supplier)} overload defers message construction so it costs
 * nothing when debug-level output is disabled — use it on hot paths.
 */
public final class ComponentReporter {

    private final String   source;
    private volatile Reporter sink;

    /**
     * @param source the component name this handle logs under
     * @param sink   the delivery sink (e.g. {@link Slf4jReporter#shared()})
     */
    public ComponentReporter(String source, Reporter sink) {
        this.source = Objects.requireNonNull(source, "source");
        this.sink   = sink;
    }

    /** Redirects this component's output to a different sink. */
    public void sink(Reporter sink) {
        this.sink = sink;
    }

    /** Verbose diagnostic output (maps to debug). */
    public void largeInfo(String message) {
        emit(ReportLevel.LARGEINFO, message);
    }

    /** Verbose diagnostic output, lazily constructed; ideal for hot paths. */
    public void largeInfo(Supplier<String> message) {
        Reporter s = sink;
        if (s != null && s.isEnabled(source, ReportLevel.LARGEINFO)) {
            s.report(System.currentTimeMillis(), source, message.get(), ReportLevel.LARGEINFO);
        }
    }

    /** Normal operational information. */
    public void info(String message) {
        emit(ReportLevel.INFO, message);
    }

    /** Something unexpected but recoverable. */
    public void warn(String message) {
        emit(ReportLevel.WARNING, message);
    }

    /** A serious error that may affect correctness or data integrity. */
    public void severe(String message) {
        emit(ReportLevel.SEVERE, message);
    }

    /** An unrecoverable condition. */
    public void fatal(String message) {
        emit(ReportLevel.FATAL, message);
    }

    private void emit(ReportLevel level, String message) {
        Reporter s = sink;
        if (s != null) {
            s.report(System.currentTimeMillis(), source, message, level);
        }
    }
}
