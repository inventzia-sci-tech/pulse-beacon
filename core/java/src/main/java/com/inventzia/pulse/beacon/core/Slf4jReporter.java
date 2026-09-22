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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Default {@link Reporter} that routes platform messages to SLF4J.
 *
 * <p>This is the bridge between pulse-beacon's proprietary reporting contract
 * and the JVM logging ecosystem. Each distinct {@code source} (a component name
 * such as {@code "engine"}, {@code "reader-1"} or {@code "beater"}) maps to its
 * own SLF4J {@link Logger}, so a deployment can route, level, or silence each
 * component independently through its logging backend (Logback by default).
 *
 * <p>{@link ReportLevel} maps to SLF4J levels as:
 * <ul>
 *   <li>{@link ReportLevel#LARGEINFO} → {@code debug}</li>
 *   <li>{@link ReportLevel#INFO} → {@code info}</li>
 *   <li>{@link ReportLevel#WARNING} → {@code warn}</li>
 *   <li>{@link ReportLevel#SEVERE}, {@link ReportLevel#FATAL} → {@code error}</li>
 * </ul>
 *
 * <p>The {@code timestamp} argument is the platform/simulation time of the
 * event; it is left for callers to embed in the message where it is meaningful
 * (e.g. during compressed-time replay, when wall-clock time is not), so this
 * reporter does not prepend it. The backend supplies the wall-clock timestamp.
 *
 * <p>Thread-safe and cheap to share: use {@link #shared()}.
 */
public final class Slf4jReporter implements Reporter {

    private static final Slf4jReporter SHARED = new Slf4jReporter();

    /** @return the process-wide shared instance. */
    public static Slf4jReporter shared() {
        return SHARED;
    }

    private final Map<String, Logger> loggers = new ConcurrentHashMap<>();

    /**
     * Side observers of the stream (see {@link ReportTap}); normally empty, so the per-message cost is
     * a single {@code isEmpty()} check. A per-run {@code console.log} attaches one here for the run's
     * lifetime. Copy-on-write so a message can fan out without a lock while taps attach and detach.
     */
    private final List<ReportTap> taps = new CopyOnWriteArrayList<>();

    /** Attach a {@link ReportTap} to receive a copy of every delivered message (ignored if {@code null}). */
    public void addTap(ReportTap tap) {
        if (tap != null) taps.add(tap);
    }

    /** Detach a previously attached {@link ReportTap}. */
    public void removeTap(ReportTap tap) {
        taps.remove(tap);
    }

    @Override
    public void report(long timestamp, String source, String message, ReportLevel level) {
        Logger logger = loggers.computeIfAbsent(source, LoggerFactory::getLogger);
        switch (level) {
            case LARGEINFO -> logger.debug(message);
            case INFO      -> logger.info(message);
            case WARNING   -> logger.warn(message);
            case SEVERE, FATAL -> logger.error(message);
        }
        // Fan out to any attached side observers (e.g. a per-run console.log). A tap failure is
        // isolated: reporting must never fail because an observer did.
        if (!taps.isEmpty()) {
            for (ReportTap tap : taps) {
                try {
                    tap.onReport(timestamp, source, message, level);
                } catch (Throwable ignore) {
                    // a broken tap must not break logging
                }
            }
        }
    }

    @Override
    public boolean isEnabled(String source, ReportLevel level) {
        Logger logger = loggers.computeIfAbsent(source, LoggerFactory::getLogger);
        return switch (level) {
            case LARGEINFO     -> logger.isDebugEnabled();
            case INFO          -> logger.isInfoEnabled();
            case WARNING       -> logger.isWarnEnabled();
            case SEVERE, FATAL -> logger.isErrorEnabled();
        };
    }
}
