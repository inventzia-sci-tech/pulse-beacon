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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    @Override
    public void report(long timestamp, String source, String message, ReportLevel level) {
        Logger logger = loggers.computeIfAbsent(source, LoggerFactory::getLogger);
        switch (level) {
            case LARGEINFO -> logger.debug(message);
            case INFO      -> logger.info(message);
            case WARNING   -> logger.warn(message);
            case SEVERE, FATAL -> logger.error(message);
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
